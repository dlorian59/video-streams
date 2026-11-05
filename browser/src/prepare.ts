import { readBody, abortable } from './body.js';
import { checkedUrl, type RequestOptions } from './loader.js';
export interface PreparedVideo {
  readonly status: 'ready';
  readonly generation: string;
  readonly playlistUrl: string;
  readonly durationSeconds: number;
  readonly segmentCount: number;
}
export class VideoPreparationError extends Error {
  constructor(message: string, readonly outcome: 'rejected' | 'unknown', options?: ErrorOptions) { super(message, options); this.name = 'VideoPreparationError'; }
}
export const preparationDefaults = Object.freeze({ timeoutMs: 190_000 });
export interface PrepareVideoOptions extends RequestOptions {
  readonly timeoutMs?: number;
  readonly prepareUrl: string;
  readonly sourceKey: string;
  readonly scope?: string;
  readonly signal?: AbortSignal;
  readonly allowedOrigins?: readonly string[];
}
export function validatePreparedVideo(value: unknown, base: string, origins: ReadonlySet<string>): PreparedVideo {
  if (!value || typeof value !== 'object') throw new Error('Invalid prepared video response');
  const record = value as Record<string, unknown>;
  if (record.status !== 'ready' || typeof record.generation !== 'string' || !/^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(record.generation)
    || typeof record.playlistUrl !== 'string' || typeof record.durationSeconds !== 'number' || !Number.isFinite(record.durationSeconds) || record.durationSeconds <= 0
    || !Number.isSafeInteger(record.segmentCount) || Number(record.segmentCount) < 1) throw new Error('Invalid prepared video response');
  const url = checkedUrl(record.playlistUrl, base, origins);
  return Object.freeze({ status: 'ready', generation: record.generation, playlistUrl: url.href, durationSeconds: record.durationSeconds, segmentCount: Number(record.segmentCount) });
}
// One request waits for the committed playlist. A lost response never implies rollback.
// Один запрос ждёт подтверждённый плейлист. Потеря ответа не означает откат.
export async function prepareVideo(options: PrepareVideoOptions): Promise<PreparedVideo> {
  options.signal?.throwIfAborted();
  const timeoutMs = options.timeoutMs ?? preparationDefaults.timeoutMs;
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 3_660_000) throw new RangeError('timeoutMs must be between 1 and 3660000');
  const base = typeof location === 'undefined' ? options.prepareUrl : location.href;
  const baseUrl = new URL(base); const origins = new Set([baseUrl.origin, ...(options.allowedOrigins ?? []).map(value => new URL(value).origin)]);
  const url = checkedUrl(options.prepareUrl, base, origins);
  const controller = new AbortController(); const abort = () => controller.abort(options.signal?.reason);
  options.signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => controller.abort(new Error('Preparation deadline exceeded')), timeoutMs);
  try {
    const headers = new Headers(typeof options.headers === 'function' ? await abortable(Promise.resolve(options.headers({ url: url.href, method: 'POST', signal: controller.signal, scope: options.scope })), controller.signal) : options.headers);
    controller.signal.throwIfAborted(); headers.set('Content-Type', 'application/json');
    const response = await abortable((options.fetch ?? globalThis.fetch)(url, { method: 'POST', headers, body: JSON.stringify({ sourceKey: options.sourceKey }),
      credentials: options.credentials ?? 'same-origin', signal: controller.signal, redirect: 'error', cache: 'no-store' }), controller.signal, late => { void late.body?.cancel().catch(() => {}); });
    if (response.status !== 201) {
      void response.body?.cancel().catch(() => {});
      throw new VideoPreparationError(`Preparation request failed (${response.status})`, [400, 401, 403, 404, 413, 415, 422, 429].includes(response.status) ? 'rejected' : 'unknown');
    }
    const body = await readBody(response, 16_384, controller.signal);
    controller.signal.throwIfAborted();
    return validatePreparedVideo(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body)), url.href, origins);
  } catch (error) {
    if (error instanceof VideoPreparationError) throw error;
    throw new VideoPreparationError('Preparation result is unknown; check application state before retrying', 'unknown', { cause: error });
  } finally { clearTimeout(timer); options.signal?.removeEventListener('abort', abort); }
}
