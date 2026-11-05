import { readBody, abortable } from './body.js';
import { LoadStats, type Loader, type LoaderCallbacks, type LoaderConfiguration, type LoaderContext } from 'hls.js';

export interface VideoRequestContext { readonly url: string; readonly method: 'GET' | 'POST'; readonly signal: AbortSignal; readonly scope: string | undefined; }
export interface RequestOptions {
  readonly headers?: HeadersInit | ((context: VideoRequestContext) => HeadersInit | Promise<HeadersInit>);
  readonly credentials?: RequestCredentials;
  readonly fetch?: typeof fetch;
}
export function checkedUrl(input: string, base: string, origins: ReadonlySet<string>): URL {
  const url = new URL(input, base);
  if (!origins.has(url.origin) || url.username || url.password || !['https:', 'http:'].includes(url.protocol)) {
    throw new Error('Video URL is outside the allowed origins');
  }
  return url;
}

// Every loader belongs to one playback lifetime; no shared credentials or complete-file cache.
// Каждый loader принадлежит одной сессии; общих credentials и кэша целых файлов нет.
export function scopedLoader(options: RequestOptions, origin: URL, origins: ReadonlySet<string>, lifetime: AbortSignal,
  maxSegmentBytes: number, scope: string, onBytes: (bytes: number) => void): new (...args: unknown[]) => Loader<LoaderContext> {
  return class VideoLoader implements Loader<LoaderContext> {
    context: LoaderContext | null = null;
    stats = new LoadStats();
    private controller = new AbortController();
    private response: Response | undefined;
    private timer: ReturnType<typeof setTimeout> | undefined;
    private detach: (() => void) | undefined;
    private active = true;
    abort() { this.active = false; this.stats.aborted = true; this.controller.abort(); this.cleanup(); }
    destroy() { this.abort(); }
    getResponseHeader(name: string) { return this.response?.headers.get(name) ?? null; }
    private cleanup() { clearTimeout(this.timer); this.detach?.(); this.detach = undefined; }
    load(context: LoaderContext, config: LoaderConfiguration, callbacks: LoaderCallbacks<LoaderContext>) {
      this.context = context;
      this.stats.loading.start = performance.now();
      const abort = () => this.abort(); lifetime.addEventListener('abort', abort, { once: true });
      this.detach = () => lifetime.removeEventListener('abort', abort);
      if (lifetime.aborted) { this.abort(); return; }
      this.timer = setTimeout(() => {
        if (!this.active) return;
        this.abort(); callbacks.onTimeout(this.stats, context, null);
      }, Math.min(config.loadPolicy.maxLoadTimeMs, 30_000));
      void this.read(context).then(data => {
        if (!this.active || lifetime.aborted) return;
        this.cleanup(); this.stats.loading.end = performance.now();
        this.active = false;
        callbacks.onSuccess({ url: this.response!.url || context.url, data, code: this.response!.status }, this.stats, context, this.response!);
      }, error => {
        if (!this.active || lifetime.aborted) return;
        this.cleanup(); this.active = false;
        callbacks.onError({ code: this.response?.status ?? 0, text: error instanceof Error ? error.message : 'Video request failed' }, context, this.response ?? null, this.stats);
      });
    }
    private async read(context: LoaderContext): Promise<string | ArrayBuffer> {
      const url = checkedUrl(context.url, origin.href, origins);
      const headers = new Headers(typeof options.headers === 'function' ? await abortable(Promise.resolve(options.headers({ url: url.href, method: 'GET', signal: this.controller.signal, scope })), this.controller.signal) : options.headers);
      this.controller.signal.throwIfAborted(); lifetime.throwIfAborted();
      if (context.rangeEnd) {
        if (!Number.isSafeInteger(context.rangeStart) || context.rangeStart! < 0 || !Number.isSafeInteger(context.rangeEnd) || context.rangeEnd <= context.rangeStart!) throw new Error('Invalid HLS byte range');
        headers.set('Range', `bytes=${context.rangeStart}-${context.rangeEnd - 1}`);
      }
      const response = await abortable((options.fetch ?? globalThis.fetch)(url, { headers, signal: this.controller.signal,
        credentials: options.credentials ?? 'same-origin', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer' }), this.controller.signal, late => { void late.body?.cancel().catch(() => {}); });
      this.response = response; this.stats.loading.first = performance.now();
      const limit = context.responseType === 'arraybuffer' ? maxSegmentBytes : 1_048_576;
      if (!response.ok) { void response.body?.cancel().catch(() => {}); throw new Error(`Video request failed (${response.status})`); }
      const data = await readBody(response, limit, this.controller.signal, bytes => {
        this.stats.loaded += bytes; this.stats.chunkCount++; onBytes(bytes);
      });
      this.stats.total = data.byteLength;
      if (context.responseType === 'arraybuffer') return data.buffer;
      const text = new TextDecoder('utf-8', { fatal: true }).decode(data);
      if (!text.startsWith('#EXTM3U') || !text.includes('#EXT-X-ENDLIST') || !text.includes('#EXT-X-MAP:') || text.includes('#EXT-X-KEY:')) {
        throw new Error('Expected an unencrypted fMP4 HLS VOD media playlist');
      }
      return text;
    }
  };
}
