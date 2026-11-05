# Video Streams 0.1.0 — from an uploaded clip to playback

[README](../../README.md) · [Versions / Версии](../index.md) · [English](en.md) · [Русский](ru.md) · [API](reference.en.md)

## One clip, three separate outcomes

A successful file upload means the original object exists. A ready video means its media segments and final playlist have been stored. Playing means the browser has fetched enough data and decoded it. Keep these states separate in the UI: upload → prepare → ready → play. A failed prepare does not invalidate a committed source, and a browser decode error does not imply the source upload failed.

Use this add-on for short recorded videos whose storage and authorization already belong to your application. V1 is a fixed-quality video-on-demand pipeline. A live stream, encrypted input, a multi-quality ladder or a durable media-job service needs a different contract; none is simulated by this package.

```text
committed source object → prepare → immutable HLS generation → browser session
```

The ordinary file-streams upload/download stays one stream. HLS segments are a separate representation made for playback, not application-level slicing of the original upload.

## 1. Reuse storage and prepare a committed source

Use the Java dependency in the README. The core accepts an existing ObjectStreams and a private writable directory. A full Java 17+ runtime, a POSIX-compatible work filesystem and external ffmpeg/ffprobe with libx264/AAC support are needed. The library does not download binaries or provision storage.

```java
var options = VideoOptions.defaults(privateWorkDirectory);
var videos = new VideoStreams(applicationStorage, options);
Mono<VideoAsset> result = videos.prepare(authorizedSourceKey);
```

Create the service once and close it when the host shuts down. The Mono is the preparation result, not a stored job ID; let your WebFlux service return it or let your own worker subscribe. Do not close VideoStreams immediately after constructing an unsubscribed Mono. See the complete [PrepareVideo.java](../../examples/java/PrepareVideo.java).

The host must authorize the source key first and keep that source immutable during preparation. Repeating prepare creates another generation; it is not idempotent deduplication. No automatic retry or durable queue is installed. If your application needs queued preparation, invoke this same method from its existing durable job handler and persist the returned generation.

VideoAsset contains generation, sourceKey, playlistKey, mimeType, durationSeconds and segmentCount. playlistKey is a storage key, not a public URL. Generate a host-authorized playback URL only after preparation succeeds.

## 2. Deliver assets through existing authorization

VideoAssetHandler reuses the existing file-streams HTTP adapter. Your route authenticates and authorizes the source on every GET before delegating:

```java
return assetHandler.handle(exchange, authorizedSourceKey, generation, name);
```

The handler accepts only playlist.m3u8, init.mp4 and segment-NNNNNN.m4s names within the computed generation namespace. It serves GET only; knowing a generation UUID does not authorize a request. Source-key authorization also exposes historical generations unless your application checks which generations remain permitted.

| Route in the full example | Meaning |
|---|---|
| PUT /api/video-sources/{id} | Existing continuous file upload; wait for committed=true and retain its key. |
| POST /api/videos/{id}/prepare | Body {"sourceKey":"id"}; waits for readiness and responds 201, not 202. |
| GET /api/videos/{id}/assets/{generation}/{name} | Reauthorizes each playlist/init/segment request. |

```json
{
  "status": "ready",
  "generation": "2a15bb1b-f405-46a2-b23f-a5102ed4a541",
  "playlistUrl": "/api/videos/demo%3Aclip/assets/2a15bb1b-f405-46a2-b23f-a5102ed4a541/playlist.m3u8",
  "durationSeconds": 12.0,
  "segmentCount": 6
}
```

This JSON illustrates the transport shape, not a measured result. The secured example uses id=account:source, and its upload commit key equals the same id. Treat the account-prefix policy as an example: replace it with your persisted ACL before production use. Keep CSRF protection on session-authenticated upload/prepare requests. Playlist references are relative filenames without credentials.

## 3. Attach a browser session

Add @59alex/video-streams to your package manifest as shown in the README. The [player.ts](../../examples/browser/player.ts) function uses the existing session and CSRF fields to await preparation, then attaches playback. It returns the ready asset and VideoSession without auto-playing.

```ts
const controller = new AbortController();
const { session } = await prepareAndAttach(video, sourceKey, identity, controller.signal, showState);
```

The fragment calls the exported function from the complete source; identity contains scope, csrfHeader and csrfToken from your application. The library never creates credentials. scope labels the current account/session for transport hooks; it is not an authorization token or server ACL. Recreate/abort the playback lifetime on account change.

```ts
await session.play();
session.pause();
session.seek(30);
session.dispose();
controller.abort();
```

Call play from a user gesture and show a useful UI if its promise rejects. The lines above illustrate separate actions, not a sequence to execute immediately. dispose is idempotent and aborts owned requests/listeners/player resources. Attach to an already owned video element replaces the earlier session. Dispose/abort on unmount, source change and logout. Do not automatically repeat upload or prepare after an unknown outcome.

onState/subscribe report loading, ready, playing, paused, buffering, ended, error and disposed with time, duration, buffered seconds and received-byte/segment counters. Preparation itself is a separate promise; these playback phases do not claim that a source was uploaded.

<a id="full-stack"></a>
## 4. Use the complete Spring + React integration

For Boot, prefer the companion starter rather than constructing process/storage lifecycle beans yourself. It reuses file-streams beans, configures VideoStreams and VideoAssetHandler, and installs no public routes.

```xml
<dependency>
  <groupId>io.github.59alex</groupId>
  <artifactId>video-streams-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```yaml
file-streams:
  enabled: true
  http:
    enabled: false
  limits:
    profile: MEDIA
video-streams:
  enabled: true
  work-directory: ${VIDEO_WORK_DIRECTORY}
```

Retain your configured storage client, bucket credentials and existing security. The snippet disables generic public file routes; the example controller applies policy before delegation. The full sample also configures HTTPS and HttpOnly session cookies. Do not copy demo login policy into a production authentication system.

```text
video-streams-spring-boot-starter/examples/secured-video/
  src/main/java/example/video/
    VideoController.java   # authorization and HTTP routes
    VideoService.java      # ready response after prepare
    DemoSecurity.java      # example-only session/CSRF policy
    SessionController.java # session/CSRF information
  src/main/resources/application.yml
video-streams/examples/browser/player.ts
video-streams-react/examples/spring-react/VideoMessage.tsx
```

Complete commented source and walkthroughs:

- [Spring service/controller and consumer](https://github.com/dlorian59/video-streams-spring-boot-starter/tree/main/examples/secured-video)
- [Starter README](https://github.com/dlorian59/video-streams-spring-boot-starter)
- [React attachment/player](https://github.com/dlorian59/video-streams-react)
- [Spring/React browser example](https://github.com/dlorian59/video-streams-react/tree/main/examples/spring-react)

These links are publication destinations. Until repositories and packages are published, use the corresponding adjacent local checkouts and the CONTRIBUTING instructions. Their presence here is not a claim that GitHub or a package registry already serves them.

## 5. Choose resource budgets before accepting work

VideoOptions.defaults requires your work directory and supplies a 3-minute deadline, 512 MiB input, 512 MiB output, 300 segments and 2 concurrent preparations. Admission is bounded; BUSY is backpressure, not an unbounded waiting queue. The default segment budget also limits input duration to 600 seconds with the fixed 2-second target. Long/high-bitrate sources may hit byte/time limits earlier.

Input is downloaded to a private temporary file; preparation uses local disk rather than aggregating the complete source in JVM heap. Output inventory is monitored and validated before publication. Size checks poll every 100 ms and can overshoot between observations and cannot enforce an OS filesystem quota. Provision disk for concurrent source/output work plus overhead; use host CPU/memory/disk isolation and maintain patched FFmpeg binaries for untrusted media.

Browser preview targets 4 seconds ahead; playing targets 12 seconds ahead and 8 behind in preview profile, or 24 ahead/12 behind in playback profile. These are hls.js buffering goals. One fragment, browser decoder, request buffers and retained media can make actual memory higher. No hard total-browser-memory bound is promised.

The loader limits each media response to 16 MiB and playlist response to 1 MiB, disallows redirects, and has bounded request time. preload:none still fetches the playlist but defers media until playback. There is no automatic network retry, shared persistent playback cache or native-HLS fallback.

## 6. Codec and format boundaries

Accepted sources are MP4/MOV with an ftyp signature and Matroska/WebM with a finite positive duration and a video stream. Input dimensions are bounded; audio-only, playlist/URL sources, encrypted media and unsupported container signatures are rejected. Probe success is not a promise that every damaged file can decode.

The fixed profile selects the first video and optional first audio stream, preserves aspect ratio within 1280×720 without upscaling, outputs yuv420p H.264 High 3.1 at 30 fps and AAC 128 kbit/s when audio exists. It does not preserve every source track, HDR semantics, subtitles or chapters. Keyframes support approximately two-second HLS fMP4 segments; the last segment can be shorter.

The output is a media playlist with init.mp4 and relative media filenames, not a master playlist or ABR ladder. See the underlying [FFmpeg HLS muxer](https://www.ffmpeg.org/ffmpeg-formats.html#hls) and [hls.js compatibility](https://github.com/video-dev/hls.js#compatibility). Browser MSE/codec support is required. HLS as a format does not guarantee all browsers can decode this profile.

## 7. Cancellation, publication and recovery

Preparation uploads initialization/media first and the playlist last. Until the final playlist commits, the generation should not be offered to viewers. A transport failure or cancellation during that final upload can leave the result unknown: the object may have committed even though no ready response was received.

ObjectStreams has no delete API. Failure may leave unreferenced immutable parts; use an application-owned retention/lifecycle policy. Never assume cancellation rolled back uploads, deleted the original or removed a possibly committed playlist. Persist successful VideoAsset metadata on the server before returning ready if you need to recover a lost HTTP response or limit historical access. If storage publication itself is uncertain, the call may return no asset/generation; the library has no discovery endpoint for it. Reconciliation then requires your storage/application policy.

Browser prepareVideo classifies explicit rejections separately from unknown outcomes. An unknown result must be presented as uncertain, not as guaranteed failure with an automatic retry. Decide recovery through application state; this library has no hidden polling/status endpoint.

## What to retain

Storage owns the original, preparation owns temporary work and one generation, and the browser owns one playback lifetime. Authenticate each asset request, keep source IDs stable, publish readiness only after the playlist, and distinguish byte limits from host/browser resource guarantees. The API removes media plumbing without hiding authorization, persistence or uncertain outcomes.

[API and errors](reference.en.md) · [Verification / Проверки](../VERIFICATION.md) · [CONTRIBUTING](../../CONTRIBUTING.md) · [README](../../README.md)
