# @59alex/video-streams

<details name="readme-language" open><summary>English</summary>

Authenticated HLS sessions for existing video elements: preload a short beginning, continue during playback and seek without downloading a complete Blob. FFmpeg preparation and Spring delivery live in the [repository guide](https://github.com/dlorian59/video-streams/blob/main/README.md).

```json
{"dependencies":{"@59alex/video-streams":"^0.1.0"}}
```

Version 0.1.0 is source-only; registry publication is pending. Use the local artifact workflow in [CONTRIBUTING](https://github.com/dlorian59/video-streams/blob/main/CONTRIBUTING.md) to try it.

```ts
import { createVideoStreams } from '@59alex/video-streams';
const session = createVideoStreams().attach(videoElement, {
  playlistUrl: asset.playlistUrl, scope: account.id, preload: 'preview',
});
// Dispose when the element or account leaves its owner.
// Освободите сессию при смене элемента или аккаунта.
const cleanup = () => session.dispose();
```

hls.js owns playback. Buffer windows are targets; the wrapper bounds individual responses, not browser-process memory. No native HLS fallback, full-file cache, automatic preparation retry or ABR ladder in V1. [Complete examples and reference](https://github.com/dlorian59/video-streams/blob/main/docs/index.md).

</details>
<details name="readme-language"><summary>Русский</summary>

Авторизованные HLS-сессии для существующего video element: короткая предзагрузка начала, продолжение по мере просмотра и перемотка без целого Blob. Подготовка FFmpeg и доставка Spring описаны в [корневом руководстве](https://github.com/dlorian59/video-streams/blob/main/README.md).

```json
{"dependencies":{"@59alex/video-streams":"^0.1.0"}}
```

```ts
import { createVideoStreams } from '@59alex/video-streams';
const session = createVideoStreams().attach(videoElement, {
  playlistUrl: asset.playlistUrl, scope: account.id, preload: 'preview',
});
const cleanup = () => session.dispose();
```
 Версия 0.1.0 пока доступна как исходники; публикация в registry ожидается. Для локальных артефактов используйте [CONTRIBUTING](https://github.com/dlorian59/video-streams/blob/main/CONTRIBUTING.md).

Воспроизведением управляет hls.js. Окно буфера — целевое значение; ограничивается размер отдельного ответа, а не вся память браузера. В V1 нет native HLS fallback, кэша полного файла, автоматического повтора подготовки или ABR. [Примеры и справочник](https://github.com/dlorian59/video-streams/blob/main/docs/index.md).

</details>
