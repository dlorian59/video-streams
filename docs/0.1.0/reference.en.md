# 0.1.0 — API, defaults and failure reference

[Guide / Руководство](en.md) · [Versions / Версии](../index.md) · [English](reference.en.md) · [Русский](reference.ru.md) · [README](../../README.md)

## Java core

| API | Purpose |
|---|---|
| VideoStreams(ObjectStreams, VideoOptions) | Owns preparation attempts and local process/workspace resources; retains externally owned storage. |
| prepare(String sourceKey) → Mono<VideoAsset> | Cold preparation of a committed source. Each subscription is another attempt/generation; no automatic replay or durable job. |
| close() | Rejects later work, cancels active attempts and waits up to a shared six-second deadline for cleanup. It does not close storage or delete uploaded assets. |
| VideoStreams.assetKey(sourceKey, generation, name) | Deterministic storage lookup after host authorization, not a URL or access grant. |
| VideoAsset.assetKey(name) | The same artifact lookup for this immutable generation. |

Source keys use file-streams validation: 1..1024 UTF-8 bytes, no NUL; null/invalid keys can produce ordinary argument exceptions, not necessarily VideoFailure. Names are limited to playlist.m3u8, init.mp4 and segment-[0-9]{6}.m4s. Keys are stored under video-streams/SHA256(sourceKey)/generation/name. Hashing separates namespaces; it is not encryption or authorization.

VideoAsset fields are generation (UUID), sourceKey, playlistKey, mimeType, durationSeconds and segmentCount. MIME is application/vnd.apple.mpegurl; duration is finite/positive and at least one segment is required. The original source is neither changed nor deleted.

close() throws IllegalStateException if cleanup cannot complete, a slot remains quarantined or the caller is interrupted. Interruption is preserved. If invoked from the owning preparation worker, it cannot join itself; that attempt completes cleanup when the call returns to the worker. Call close from the host shutdown lifecycle, not a request event loop.

[VideoStreams](../../core/src/main/java/io/github/alex59/videostreams/VideoStreams.java) · [VideoAsset](../../core/src/main/java/io/github/alex59/videostreams/VideoAsset.java) · [Complete service](../../examples/java/PrepareVideo.java)

## VideoOptions

| Field | Default | Contract |
|---|---|---|
| workDirectory | Required Path | Absolute normalized path; private attempt directories need POSIX permissions. |
| ffmpegCommand | ffmpeg | Nonblank executable name/path, no shell command string or user flags. |
| ffprobeCommand | ffprobe | Nonblank executable name/path. |
| timeout | 3 minutes | Positive, at most one hour; covers the operation, not a CPU quota. |
| maxInputBytes | 512 MiB | Positive source bound; declared length and actual stream are checked. |
| maxOutputBytes | 512 MiB | Positive total output budget; periodic inspection, not a hard filesystem quota. |
| maxSegments | 300 | 1..10000; also limits probed input duration to count × 2 seconds. |
| maxConcurrent | 2 | 1..64 per service instance; no implicit distributed coordination. |

Use VideoOptions.defaults(workDirectory) for the normal profile. Use the record constructor when the host deliberately supplies different budgets. Fixed safety bounds are exposed as constants: DEFAULT_MAX_BYTES=512 MiB, MAX_SEGMENT_BYTES=16 MiB, MAX_SOURCE_BUFFER_BYTES=1 MiB, MAX_PLAYLIST_BYTES=256 KiB. These distinguish one source buffer, one output artifact and the total output budget.

Admission slots remain occupied until the owned process and private workspace are cleaned. Failed cleanup quarantines a slot instead of admitting replacement work over live resources. Investigate the process/work directory and host shutdown policy; creating unlimited replacement service instances defeats per-instance admission limits. A cancelled subscriber does not receive a guaranteed terminal error callback.

If the server timeout is raised beyond the fixed 190-second prepareVideo budget, the browser may abort first. Align server/proxy/client deadlines or use your own explicitly bounded preparation transport. A longer server deadline does not create a status endpoint or durable job.

[Options source](../../core/src/main/java/io/github/alex59/videostreams/VideoOptions.java)

## Java failures

| VideoFailure.Code | Meaning and response |
|---|---|
| INVALID_SOURCE | Empty/unsupported media, invalid dimensions/duration or unsupported inventory. Correct the source; do not blindly retry. |
| TOO_LARGE | Input/output/segment budget exceeded. Select a suitable source or deliberately revise host budgets. |
| BUSY | All service slots are occupied. Apply application backpressure or an existing durable queue. |
| PROCESS_FAILED | FFmpeg/probe/validation/cleanup failed. Inspect deployment and source; readiness is not implied. |
| TIMEOUT | Preparation deadline elapsed. A publication outcome may be unknown. |
| CANCELLED | Preparation was cancelled. It is not storage rollback. |
| STORAGE_FAILED | Source read or artifact publication failed; inspect cause/application state before repeating. |
| CLOSED | The service has been closed; use the correctly scoped host lifecycle. |

VideoFailure.code() is a core category, not a universal HTTP status mapping. The companion example supplies its own API errors. Storage failures/cancellation near final playlist upload may leave already committed objects. ObjectStreams does not provide deletion, so retention is application-owned.

## WebFlux delivery

VideoAssetHandler(ObjectStreams, StreamLimits) delegates to the existing ObjectStreamHandler. handle(exchange, sourceKey, generation, name) returns Mono<Void>. Authorize the source and permitted generation before calling it. Only GET is accepted (other methods receive 405); invalid generated names receive 400. Storage/range/body behavior is inherited from file-streams. No route, security filter, session or migration is installed.

[Handler](../../webflux/src/main/java/io/github/alex59/videostreams/webflux/VideoAssetHandler.java) · [Integration](en.md#full-stack)

## Browser API

| API | Behavior |
|---|---|
| createVideoStreams(options?) | Creates a player factory. profile is preview by default, or playback. |
| attach(video, options) | Requires playlistUrl and nonblank scope; optional preload, signal, onState. Returns VideoSession; replaces an older session on the same element. |
| getState() / subscribe(listener) | Immutable current state; subscribe immediately receives it and returns an unsubscribe function. |
| play() / pause() / seek(seconds) | Control the video; play is asynchronous and may reject, seek needs a finite nonnegative time. |
| dispose() | Idempotent player teardown and request cancellation; no server asset deletion. |
| prepareVideo(options) | POSTs sourceKey JSON, waits for HTTP 201 ready metadata, validates response, resolves PreparedVideo. |
| validatePreparedVideo(value, base, origins) | Validates ready shape, UUID/duration/count and permitted playlist URL for custom application transports. |

Shared transport options are headers (HeadersInit or an async context callback), credentials (same-origin default), fetch override and explicitly trusted allowedOrigins on create/prepare. Header context contains url, method, signal and scope. Prepare additionally requires prepareUrl and sourceKey; scope and AbortSignal are optional. The complete example passes scope to both prepare and attach.

Same-origin URLs are the default. Extra allowedOrigins are also permission to send configured authorization headers there. Redirects and URLs containing username/password are rejected. Use cookies/headers, never put bearer credentials in playlist URLs. scope is a client label, not server authentication or a cryptographic partition.

| videoProfiles | Preview target | Forward target while playing | Back target |
|---|---|---|---|
| preview | 4 s | 12 s | 8 s |
| playback | 4 s | 24 s | 12 s |

Both profiles cap a media response at 16 MiB; playlist responses are capped at 1 MiB. Preparation JSON is capped at 16 KiB with a 190-second client deadline. Media request deadlines are at most 30 seconds. These limits do not cap all browser memory or total transferred bytes. hls.js schedules and evicts playback buffers. preload:none still requests the manifest. There are no automatic network retries.

VideoState fields: phase, currentTime, duration, bufferedSeconds, receivedBytes, loadedSegments and optional error. bufferedSeconds is the contiguous window ahead of current time, not total allocated memory. loadedSegments counts distinct buffered fragment URLs in that session; receivedBytes is cumulative network body data observed by the loader.

VideoPreparationError.outcome is rejected for explicit 400/401/403/404/413/415/422/429 responses and unknown for other statuses, lost/invalid responses, transport errors or cancellation after starting. A signal already aborted before the call may throw its original abort reason. Rejected means the API rejected the request, not a general proof of storage rollback; unknown never authorizes an automatic replay.

[Browser source](../../browser/src/index.ts) · [Preparation transport](../../browser/src/prepare.ts) · [Complete example](../../examples/browser/player.ts)

## Runtime and language baseline

Library version 0.1.0 is independent of Java/TypeScript compiler versions. Java targets release 17, using records, standard Process/Path APIs and Reactor; LTS 21/25 and optional 27 do not select extra features. Browser sources use TypeScript 7.0.2 to emit ES2022 ESM with type declarations. Runtime requirements include fetch/streams, AbortController, HTMLVideoElement and a supported hls.js/MediaSource + codec combination. No preview Java flags or native-HLS/whole-Blob fallback are supplied.

MIT covers this library. hls.js keeps its upstream license; external FFmpeg builds and enabled codecs have their own licensing/deployment terms. No third-party notices are removed. Tests and environment limits are recorded in VERIFICATION, not inferred from the runtime target.

[Guide](en.md) · [Verification](../VERIFICATION.md) · [README](../../README.md)

A custom fetch implementation must honor the provided AbortSignal and redirect/credential policies. Overriding transport does not transfer application authentication or resource policy into the library.
