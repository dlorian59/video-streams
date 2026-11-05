import Hls from 'hls.js';
import { checkedUrl, scopedLoader, type RequestOptions } from './loader.js';
export type { RequestOptions, VideoRequestContext } from './loader.js';
export const videoProfiles = Object.freeze({
  preview: Object.freeze({ previewSeconds: 4, forwardSeconds: 12, backSeconds: 8, maxSegmentBytes: 16_777_216 }),
  playback: Object.freeze({ previewSeconds: 4, forwardSeconds: 24, backSeconds: 12, maxSegmentBytes: 16_777_216 }),
});
export type VideoProfile = keyof typeof videoProfiles;
export type VideoPhase = 'loading' | 'ready' | 'playing' | 'paused' | 'buffering' | 'ended' | 'error' | 'disposed';
export interface VideoState {
  readonly phase: VideoPhase;
  readonly currentTime: number;
  readonly duration: number;
  readonly bufferedSeconds: number;
  readonly receivedBytes: number;
  readonly loadedSegments: number;
  readonly error?: Error;
}
export interface VideoStreamsOptions extends RequestOptions {
  readonly profile?: VideoProfile;
  /** Additional origins trusted to receive the supplied authorization headers.
   * Дополнительные origins, которым разрешено получать переданные заголовки авторизации. */
  readonly allowedOrigins?: readonly string[];
}
export interface VideoAttachOptions {
  readonly playlistUrl: string;
  readonly scope: string;
  readonly preload?: 'preview' | 'none';
  readonly signal?: AbortSignal;
  readonly onState?: (state: VideoState) => void;
}
export interface VideoSession {
  getState(): VideoState;
  subscribe(listener: (state: VideoState) => void): () => void;
  play(): Promise<void>;
  pause(): void;
  seek(seconds: number): void;
  dispose(): void;
}
export interface VideoStreams { attach(video: HTMLVideoElement, options: VideoAttachOptions): VideoSession; }
const owners = new WeakMap<HTMLVideoElement, VideoSession>();
const noRetry = { default: { maxTimeToFirstByteMs: 15_000, maxLoadTimeMs: 30_000, timeoutRetry: null, errorRetry: null } };

export function createVideoStreams(options: VideoStreamsOptions = {}): VideoStreams {
  const profile = videoProfiles[options.profile ?? 'preview'];
  if (!profile) throw new TypeError('Unknown video profile');
  return { attach(video, settings) {
    if (!settings.scope.trim()) throw new TypeError('A non-empty authorization scope is required');
    if (!Hls.isSupported()) throw new Error('This browser does not support HLS through MediaSource; no complete-file fallback is used');
    settings.signal?.throwIfAborted();
    const base = new URL(video.ownerDocument.baseURI);
    const origins = new Set([base.origin, ...(options.allowedOrigins ?? []).map(value => new URL(value).origin)]);
    const playlist = checkedUrl(settings.playlistUrl, base.href, origins);
    owners.get(video)?.dispose();
    const controller = new AbortController(); const listeners = new Set<(state: VideoState) => void>();
    if (settings.onState) listeners.add(settings.onState);
    let disposed = false; let playingIntent = false; let started = false; let receivedBytes = 0;
    const segments = new Set<string>();
    let state: VideoState = Object.freeze({ phase: 'loading', currentTime: 0, duration: 0, bufferedSeconds: 0, receivedBytes: 0, loadedSegments: 0 });
    let hls: Hls;
    function ahead() { for (let i = 0; i < video.buffered.length; i++) if (video.currentTime >= video.buffered.start(i) && video.currentTime <= video.buffered.end(i)) return video.buffered.end(i) - video.currentTime; return 0; }
    function emit(phase = state.phase, error?: Error) {
      if (disposed) return;
      state = Object.freeze({ phase, currentTime: video.currentTime, duration: Number.isFinite(video.duration) ? video.duration : 0,
        bufferedSeconds: ahead(), receivedBytes, loadedSegments: segments.size, ...(error ? { error } : {}) });
      for (const listener of listeners) listener(state);
    }
    const Loader = scopedLoader(options, playlist, origins, controller.signal, profile.maxSegmentBytes, settings.scope, bytes => { receivedBytes += bytes; });
    hls = new Hls({ loader: Loader, enableWorker: false, autoStartLoad: false,
      maxBufferLength: profile.previewSeconds, maxMaxBufferLength: profile.previewSeconds,
      backBufferLength: profile.backSeconds, maxBufferSize: profile.maxSegmentBytes,
      manifestLoadPolicy: noRetry, playlistLoadPolicy: noRetry, fragLoadPolicy: noRetry, keyLoadPolicy: noRetry });
    function start() { if (disposed) return; if (!started) { started = true; hls.startLoad(video.currentTime); } hls.resumeBuffering(); }
    function onPlay() { playingIntent = true; hls.config.maxBufferLength = profile.forwardSeconds; hls.config.maxMaxBufferLength = profile.forwardSeconds; start(); emit('playing'); }
    function onPause() { playingIntent = false; hls.pauseBuffering(); emit('paused'); }
    function onSeek() { hls.config.maxBufferLength = playingIntent ? profile.forwardSeconds : profile.previewSeconds; hls.config.maxMaxBufferLength = hls.config.maxBufferLength; start(); emit('buffering'); }
    function onReady() { emit(video.paused ? 'ready' : 'playing'); }
    function onTime() { emit(); }
    function onWaiting() { emit('buffering'); }
    function onEnded() { playingIntent = false; hls.pauseBuffering(); emit('ended'); }
    function onError() { emit('error', new Error(video.error?.message || 'Media decode failed')); hls.stopLoad(); }
    const handlers = { play: onPlay, pause: onPause, seeking: onSeek, canplay: onReady, timeupdate: onTime, waiting: onWaiting, ended: onEnded, error: onError };
    for (const [event, handler] of Object.entries(handlers)) video.addEventListener(event, handler);
    hls.on(Hls.Events.MANIFEST_PARSED, () => { if (settings.preload !== 'none' || playingIntent) start(); });
    hls.on(Hls.Events.FRAG_BUFFERED, (_event, data) => {
      if (disposed) return;
      segments.add(data.frag.url); emit();
      if (!playingIntent && ahead() >= profile.previewSeconds - 0.1) hls.pauseBuffering();
    });
    hls.on(Hls.Events.ERROR, (_event, data) => { if (data.fatal) { hls.stopLoad(); emit('error', new Error(`Playback failed: ${data.details}`)); } });
    const session: VideoSession = {
      getState: () => state,
      subscribe(listener) { if (disposed) return () => {}; listeners.add(listener); listener(state); return () => { listeners.delete(listener); }; },
      play() { if (disposed) return Promise.reject(new Error('Video session is disposed')); start(); return video.play(); },
      pause() { if (!disposed) video.pause(); },
      seek(seconds) { if (disposed) throw new Error('Video session is disposed'); if (!Number.isFinite(seconds) || seconds < 0) throw new RangeError('Invalid seek time'); video.currentTime = seconds; },
      dispose() {
        if (disposed) return;
        disposed = true; controller.abort(); settings.signal?.removeEventListener('abort', onAbort);
        for (const [event, handler] of Object.entries(handlers)) video.removeEventListener(event, handler);
        hls.destroy();
        if (owners.get(video) === session) { owners.delete(video); video.pause(); video.removeAttribute('src'); video.load(); }
        state = Object.freeze({ ...state, phase: 'disposed' });
        for (const listener of listeners) listener(state); listeners.clear();
      },
    };
    function onAbort() { session.dispose(); }
    owners.set(video, session); settings.signal?.addEventListener('abort', onAbort, { once: true });
    video.preload = settings.preload === 'none' ? 'none' : 'auto';
    hls.attachMedia(video); hls.loadSource(playlist.href); emit();
    return session;
  } };
}
export { prepareVideo, preparationDefaults, validatePreparedVideo, VideoPreparationError, type PreparedVideo, type PrepareVideoOptions } from './prepare.js';
