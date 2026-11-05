# video-streams

<details name="readme-language">
<summary><picture><source media="(prefers-color-scheme: dark)" srcset="assets/lang-en-dark.svg"><img src="assets/lang-en-light.svg" width="42" height="26" alt="EN"></picture></summary>

**0.1.0 · Java 17+ · TypeScript · MIT** · [Versions / Версии](docs/index.md)

Turn an uploaded video into a playable preview and on-demand playback. Prepare the source once, show the beginning before play, and load the needed media segments while the viewer watches or seeks. The browser does not first download a complete video Blob.

Use it for video attachments, training clips and recorded messages stored through file-streams. Ordinary file upload/download remains one continuous stream; video preparation is an optional add-on. Version 0.1.0 creates one HLS VOD quality, not a live broadcast or adaptive-bitrate ladder.

## Get started in an existing application

The coordinates below describe local 0.1.0 artifacts; they are not available from Maven Central/npm yet. Supply them through your configured repository or use the local preparation guide in CONTRIBUTING. Repository publication is tracked separately from package publication.

Maven:
```xml
<dependency>
  <groupId>io.github.59alex</groupId>
  <artifactId>video-streams</artifactId>
  <version>0.1.0</version>
</dependency>
<!-- Optional HTTP delivery / Необязательная HTTP-доставка -->
<dependency>
  <groupId>io.github.59alex</groupId>
  <artifactId>video-streams-webflux</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle Kotlin:
```kotlin
dependencies {
    implementation("io.github.59alex:video-streams:0.1.0")
    implementation("io.github.59alex:video-streams-webflux:0.1.0")
}
```

Reuse your configured ObjectStreams storage, supply a private POSIX-capable work directory, and make ffmpeg/ffprobe executables available to the process. The service is application-scoped and closed at shutdown; it never closes your storage client.

```java
package example.video;

import io.github.alex59.filestreams.ObjectStreams;
import io.github.alex59.videostreams.VideoAsset;
import io.github.alex59.videostreams.VideoOptions;
import io.github.alex59.videostreams.VideoStreams;
import java.nio.file.Path;
import reactor.core.publisher.Mono;

// Create this service once; your host closes it at application shutdown.
// Создайте сервис один раз; приложение закрывает его при остановке.
public final class PrepareVideo implements AutoCloseable {
    private final VideoStreams videos;

    public PrepareVideo(ObjectStreams applicationStorage, Path privateWorkDirectory) {
        // Storage is already configured by the application and remains application-owned.
        // Хранилище уже настроено приложением и остаётся под его управлением.
        videos = new VideoStreams(applicationStorage, VideoOptions.defaults(privateWorkDirectory));
    }

    public Mono<VideoAsset> prepareAuthorizedSource(String sourceKey) {
        // Authorize the committed source before calling; this does not enqueue a durable job.
        // До вызова проверьте доступ к загруженному источнику; долговечная задача не создаётся.
        return videos.prepare(sourceKey);
    }

    @Override
    public void close() {
        // Stop owned preparation resources; the supplied ObjectStreams is not closed here.
        // Остановить ресурсы подготовки; переданный ObjectStreams здесь не закрывается.
        videos.close();
    }
}
```

Keep one PrepareVideo instance in the application, then call service.prepareAuthorizedSource(sourceKey) per request. sourceKey is an already committed, authorized object key. Subscribe/return the Mono to await a ready VideoAsset. Upload success alone does not mean preparation is finished.

[Complete Java service](examples/java/PrepareVideo.java) · [Spring Boot starter](https://github.com/dlorian59/video-streams-spring-boot-starter) · [Preparation and delivery guide](docs/0.1.0/en.md)

### Attach playback to your video element

package.json:
```json
{
  "dependencies": {
    "@59alex/video-streams": "0.1.0"
  }
}
```

The browser entry point uses an existing authenticated session and a server preparation endpoint. In this fragment video is your HTMLVideoElement, sourceKey comes from the upload commit, csrfHeaders comes from your server, signal is owned by the component, and sessionScope identifies the current account/session.

```ts
import { createVideoStreams, prepareVideo } from '@59alex/video-streams';

const ready = await prepareVideo({
  prepareUrl: `/api/videos/${encodeURIComponent(sourceKey)}/prepare`,
  sourceKey, headers: csrfHeaders, signal,
});
const session = createVideoStreams({ profile: 'preview' }).attach(video, {
  playlistUrl: ready.playlistUrl,
  scope: sessionScope,
  signal,
  onState: state => showState(state),
});
```

Result: a ready generation is attached with a small preview buffer. Call session.play() from a user gesture and handle its rejected promise; call session.dispose() and abort on unmount/logout. Unsupported MediaSource/codec environments fail instead of falling back to a whole-file download.

[Complete TypeScript source](examples/browser/player.ts) · [React component](https://github.com/dlorian59/video-streams-react) · [Full-stack walkthrough](docs/0.1.0/en.md#full-stack)

## Capabilities

- `videos.prepare(sourceKey)` — prepares an already uploaded object and completes only after the playlist is published. No job store or implicit retry.
- `VideoOptions.defaults(workDirectory)` — provides admission, input/output, segment-count and time bounds. Host CPU/disk isolation remains explicit.
- `createVideoStreams({ profile: "preview" })` — uses a 4-second preview target and loads further while playing; playback has a larger forward target.
- `session.seek(30)` — seeks through the HLS player without first materializing a complete video Blob.
- `session.dispose()` — releases the player/listeners and aborts owned requests. It does not erase server assets.

Each operation is illustrated in the [Java](examples/java/PrepareVideo.java), [TypeScript](examples/browser/player.ts) and [guide](docs/0.1.0/en.md).

## Deployment boundaries

FFmpeg/ffprobe are external dependencies. Output is H.264 High 3.1/yuv420p, up to 1280×720 at 30 fps, AAC audio when present, and roughly two-second fMP4 HLS segments. There is one rendition, no ABR, encryption, DRM, native-HLS fallback or playback cache.

Authorize the source and every playlist/segment request on your server. Playlist publication is last; cancellation near publication can leave an unknown committed outcome. Failed preparations may leave unreferenced objects for your storage retention policy. Buffer windows are player targets, not a hard cap on total browser memory.

[Guide](docs/0.1.0/en.md) · [API](docs/0.1.0/reference.en.md) · [CONTRIBUTING](CONTRIBUTING.md) · [MIT](LICENSE)

</details>

<details name="readme-language" open>
<summary><picture><source media="(prefers-color-scheme: dark)" srcset="assets/lang-ru-dark.svg"><img src="assets/lang-ru-light.svg" width="42" height="26" alt="RU"></picture></summary>

**0.1.0 · Java 17+ · TypeScript · MIT** · [Versions / Версии](docs/index.md)

Превратите загруженное видео в предварительный просмотр и воспроизведение по запросу. Подготовьте источник один раз, покажите начало до запуска и подгружайте нужные медиасегменты при просмотре и перемотке. Браузеру не нужно сначала скачивать всё видео в Blob.

Подходит для видеовложений, учебных роликов и записанных сообщений, сохранённых через file-streams. Обычная загрузка и скачивание файла остаются одним непрерывным потоком; подготовка видео — отдельное дополнение. Версия 0.1.0 создаёт одно качество HLS VOD, а не прямую трансляцию или набор адаптивных качеств.

## Подключение к существующему приложению

Координаты ниже описывают локальные артефакты 0.1.0; в Maven Central/npm они пока недоступны. Предоставьте их через ваш репозиторий либо воспользуйтесь локальной подготовкой из CONTRIBUTING. Публикация репозитория учитывается отдельно от публикации пакетов.

Maven:
```xml
<dependency>
  <groupId>io.github.59alex</groupId>
  <artifactId>video-streams</artifactId>
  <version>0.1.0</version>
</dependency>
<!-- Optional HTTP delivery / Необязательная HTTP-доставка -->
<dependency>
  <groupId>io.github.59alex</groupId>
  <artifactId>video-streams-webflux</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle Kotlin:
```kotlin
dependencies {
    implementation("io.github.59alex:video-streams:0.1.0")
    implementation("io.github.59alex:video-streams-webflux:0.1.0")
}
```

Переиспользуйте настроенный ObjectStreams, передайте приватный рабочий каталог с POSIX permissions и обеспечьте доступ процесса к ffmpeg/ffprobe. Сервис живёт вместе с приложением и закрывается при остановке; ваш storage client он не закрывает.

```java
package example.video;

import io.github.alex59.filestreams.ObjectStreams;
import io.github.alex59.videostreams.VideoAsset;
import io.github.alex59.videostreams.VideoOptions;
import io.github.alex59.videostreams.VideoStreams;
import java.nio.file.Path;
import reactor.core.publisher.Mono;

// Create this service once; your host closes it at application shutdown.
// Создайте сервис один раз; приложение закрывает его при остановке.
public final class PrepareVideo implements AutoCloseable {
    private final VideoStreams videos;

    public PrepareVideo(ObjectStreams applicationStorage, Path privateWorkDirectory) {
        // Storage is already configured by the application and remains application-owned.
        // Хранилище уже настроено приложением и остаётся под его управлением.
        videos = new VideoStreams(applicationStorage, VideoOptions.defaults(privateWorkDirectory));
    }

    public Mono<VideoAsset> prepareAuthorizedSource(String sourceKey) {
        // Authorize the committed source before calling; this does not enqueue a durable job.
        // До вызова проверьте доступ к загруженному источнику; долговечная задача не создаётся.
        return videos.prepare(sourceKey);
    }

    @Override
    public void close() {
        // Stop owned preparation resources; the supplied ObjectStreams is not closed here.
        // Остановить ресурсы подготовки; переданный ObjectStreams здесь не закрывается.
        videos.close();
    }
}
```

Храните один экземпляр PrepareVideo в приложении, затем вызывайте service.prepareAuthorizedSource(sourceKey) для каждого запроса. sourceKey — ключ уже загруженного объекта с проверенными правами. Подпишитесь либо верните Mono, чтобы дождаться готового VideoAsset. Успех загрузки ещё не означает завершение подготовки.

[Полный Java-сервис](examples/java/PrepareVideo.java) · [Spring Boot starter](https://github.com/dlorian59/video-streams-spring-boot-starter) · [Подготовка и доставка](docs/0.1.0/ru.md)

### Подключить воспроизведение к video-элементу

package.json:
```json
{
  "dependencies": {
    "@59alex/video-streams": "0.1.0"
  }
}
```

Браузерная точка входа использует существующую авторизованную сессию и endpoint подготовки. В этом фрагменте video — ваш HTMLVideoElement, sourceKey получен из результата загрузки, csrfHeaders — от сервера, signal управляется компонентом, sessionScope обозначает текущий аккаунт/сессию.

```ts
import { createVideoStreams, prepareVideo } from '@59alex/video-streams';

const ready = await prepareVideo({
  prepareUrl: `/api/videos/${encodeURIComponent(sourceKey)}/prepare`,
  sourceKey, headers: csrfHeaders, signal,
});
const session = createVideoStreams({ profile: 'preview' }).attach(video, {
  playlistUrl: ready.playlistUrl,
  scope: sessionScope,
  signal,
  onState: state => showState(state),
});
```

Результат: готовая генерация подключена с небольшим буфером предпросмотра. Вызывайте session.play() по действию пользователя и обрабатывайте отказ Promise; при уходе/выходе вызывайте session.dispose() и abort. Неподдерживаемые MediaSource/кодеки приводят к ошибке, а не скачиванию целого файла.

[Полный TypeScript-исходник](examples/browser/player.ts) · [React component](https://github.com/dlorian59/video-streams-react) · [Сквозной разбор](docs/0.1.0/ru.md#full-stack)

## Возможности

- `videos.prepare(sourceKey)` — готовит загруженный объект и завершается после публикации плейлиста. Без хранилища задач и скрытого повтора.
- `VideoOptions.defaults(workDirectory)` — задаёт пределы приёма, входа/выхода, числа сегментов и времени. Изоляция CPU/диска остаётся явной задачей хоста.
- `createVideoStreams({ profile: "preview" })` — использует цель предпросмотра 4 секунды и догружает при просмотре; playback увеличивает цель буфера вперёд.
- `session.seek(30)` — перематывает через HLS-плеер без предварительного полного Blob.
- `session.dispose()` — освобождает плеер/слушатели и отменяет свои запросы. Серверные файлы не удаляются.

Каждая операция разобрана в [Java](examples/java/PrepareVideo.java), [TypeScript](examples/browser/player.ts) и [guide](docs/0.1.0/ru.md).

## Границы применения

FFmpeg/ffprobe — внешние зависимости. Выход: H.264 High 3.1/yuv420p, до 1280×720 при 30 fps, AAC при наличии аудио и примерно двухсекундные сегменты fMP4 HLS. Доступно одно качество, без ABR, шифрования, DRM, native-HLS fallback и кэша воспроизведения.

Проверяйте права на источник и каждый запрос плейлиста/сегмента на сервере. Плейлист публикуется последним; отмена возле публикации может оставить неизвестный результат фиксации. Неудачная подготовка может оставить неиспользуемые объекты для вашей политики очистки. Окна буфера — цели плеера, не жёсткий предел всей памяти браузера.

[Руководство](docs/0.1.0/ru.md) · [API](docs/0.1.0/reference.ru.md) · [CONTRIBUTING](CONTRIBUTING.md) · [MIT](LICENSE)

</details>
