# 0.1.0 — справочник API, настроек и ошибок

[Guide / Руководство](ru.md) · [Versions / Версии](../index.md) · [English](reference.en.md) · [Русский](reference.ru.md) · [README](../../README.md)

## Java core

| API | Назначение |
|---|---|
| VideoStreams(ObjectStreams, VideoOptions) | Управляет попытками подготовки и локальными процессами/каталогами; хранилище остаётся внешним. |
| prepare(String sourceKey) → Mono<VideoAsset> | Холодная подготовка сохранённого источника. Каждая подписка — новая попытка/генерация; автоматического повтора и долговечной задачи нет. |
| close() | Отклоняет новую работу, отменяет активные попытки и ждёт очистки в пределах общего срока шесть секунд. Не закрывает хранилище и не удаляет загруженные файлы. |
| VideoStreams.assetKey(sourceKey, generation, name) | Детерминированный ключ хранилища после проверки прав хостом, не URL и не разрешение доступа. |
| VideoAsset.assetKey(name) | Тот же поиск файла внутри данной неизменяемой генерации. |

Ключ источника проверяется правилами file-streams: 1..1024 байт UTF-8, без NUL; null/неверные ключи могут вызвать обычные ошибки аргументов, не обязательно VideoFailure. Имена ограничены playlist.m3u8, init.mp4 и segment-[0-9]{6}.m4s. Ключ строится как video-streams/SHA256(sourceKey)/generation/name. Хэш разделяет пространства, но не является шифрованием или авторизацией.

Поля VideoAsset: generation (UUID), sourceKey, playlistKey, mimeType, durationSeconds и segmentCount. MIME — application/vnd.apple.mpegurl; длительность конечная/положительная, требуется хотя бы один сегмент. Исходный объект не изменяется и не удаляется.

close() бросает IllegalStateException, если очистка не завершилась, слот остался заблокирован либо вызывающий поток прерван. Признак прерывания сохраняется. При вызове из самого worker подготовки нельзя ждать себя: попытка завершает очистку после возврата управления worker. Вызывайте close из lifecycle остановки хоста, не из event loop запроса.

[VideoStreams](../../core/src/main/java/io/github/alex59/videostreams/VideoStreams.java) · [VideoAsset](../../core/src/main/java/io/github/alex59/videostreams/VideoAsset.java) · [Complete service](../../examples/java/PrepareVideo.java)

## VideoOptions

| Поле | По умолчанию | Контракт |
|---|---|---|
| workDirectory | Обязательный Path | Абсолютный нормализованный путь; приватным каталогам нужны POSIX permissions. |
| ffmpegCommand | ffmpeg | Непустое имя/путь executable, не shell-команда и не пользовательские флаги. |
| ffprobeCommand | ffprobe | Непустое имя/путь executable. |
| timeout | 3 minutes | Положительный, максимум час; охватывает операцию, не является квотой CPU. |
| maxInputBytes | 512 MiB | Положительный предел входа; проверяются объявленная длина и реальный поток. |
| maxOutputBytes | 512 MiB | Положительный бюджет выхода; периодическая проверка, не квота файловой системы. |
| maxSegments | 300 | 1..10000; также ограничивает входную длительность count × 2 секунды. |
| maxConcurrent | 2 | 1..64 на экземпляр сервиса; распределённой координации нет. |

Используйте VideoOptions.defaults(workDirectory) для обычного профиля. Конструктор record нужен при осознанном изменении бюджета хостом. Фиксированные границы доступны как константы: DEFAULT_MAX_BYTES=512 МиБ, MAX_SEGMENT_BYTES=16 МиБ, MAX_SOURCE_BUFFER_BYTES=1 МиБ, MAX_PLAYLIST_BYTES=256 КиБ. Различайте один буфер входа, один выходной файл и общий бюджет выхода.

Слот приёма занят до очистки процесса и приватного каталога. Неудачная очистка блокирует слот вместо допуска новой работы поверх оставшихся ресурсов. Проверяйте процесс/рабочий каталог и политику остановки; создание неограниченных новых сервисов обходит лимит экземпляра. Отменённый подписчик не получает гарантированного финального callback ошибки.

Если серверный timeout увеличен сверх фиксированного бюджета prepareVideo в 190 секунд, браузер может отменить запрос первым. Согласуйте сроки сервера/proxy/клиента либо используйте собственный транспорт подготовки с явным пределом. Увеличение срока не создаёт status endpoint и долговечную задачу.

[Options source](../../core/src/main/java/io/github/alex59/videostreams/VideoOptions.java)

## Java failures

| VideoFailure.Code | Значение и действие |
|---|---|
| INVALID_SOURCE | Пустое/неподдерживаемое медиа, неверные размеры/длительность. Исправьте источник; не повторяйте вслепую. |
| TOO_LARGE | Превышен бюджет входа/выхода/сегментов. Выберите подходящий источник либо осознанно пересмотрите бюджет. |
| BUSY | Все слоты сервиса заняты. Примените ограничение приёма либо существующую очередь. |
| PROCESS_FAILED | Ошибка FFmpeg/probe/проверки/очистки. Проверьте окружение и источник; готовность не подтверждена. |
| TIMEOUT | Истёк срок подготовки. Результат публикации может быть неизвестен. |
| CANCELLED | Подготовка отменена. Это не откат хранилища. |
| STORAGE_FAILED | Не удалось чтение источника или публикация; до повтора проверьте причину/состояние приложения. |
| CLOSED | Сервис закрыт; исправьте жизненный цикл хоста. |

VideoFailure.code() — категория core, не универсальная HTTP-карта. Ошибки API задаёт пример интеграции. Сбой хранилища/отмена при финальном плейлисте может оставить сохранённые объекты. У ObjectStreams нет удаления, поэтому очисткой управляет приложение.

## WebFlux delivery

VideoAssetHandler(ObjectStreams, StreamLimits) делегирует существующему ObjectStreamHandler. handle(exchange, sourceKey, generation, name) возвращает Mono<Void>. До вызова проверьте источник и допустимую генерацию. Принимается только GET (другие методы получают 405); неверные имена файлов дают 400. Поведение хранилища/range/body наследуется от file-streams. Маршрут, security filter, сессия и миграция не устанавливаются.

[Handler](../../webflux/src/main/java/io/github/alex59/videostreams/webflux/VideoAssetHandler.java) · [Integration](ru.md#full-stack)

## Browser API

| API | Поведение |
|---|---|
| createVideoStreams(options?) | Создаёт фабрику плеера. profile по умолчанию preview, альтернативный playback. |
| attach(video, options) | Требует playlistUrl и непустой scope; необязательные preload, signal, onState. Возвращает VideoSession; заменяет старую сессию элемента. |
| getState() / subscribe(listener) | Неизменяемое состояние; subscribe сразу передаёт его и возвращает функцию отписки. |
| play() / pause() / seek(seconds) | Управление видео; play асинхронен и может отказать, seek требует конечное неотрицательное время. |
| dispose() | Идемпотентное освобождение плеера и отмена запросов; без удаления серверных файлов. |
| prepareVideo(options) | Отправляет sourceKey JSON через POST, ждёт HTTP 201 ready, проверяет ответ и возвращает PreparedVideo. |
| validatePreparedVideo(value, base, origins) | Проверяет ready, UUID/длительность/число сегментов и разрешённый URL для транспорта приложения. |

Общие transport options: headers (HeadersInit либо асинхронный callback), credentials (по умолчанию same-origin), переопределение fetch и явно доверенные allowedOrigins у create/prepare. Контекст headers содержит url, method, signal и scope. Prepare дополнительно требует prepareUrl и sourceKey; scope и AbortSignal необязательны. Полный пример передаёт scope в prepare и attach.

По умолчанию допустим тот же origin. Дополнительные allowedOrigins также разрешают отправку туда настроенных заголовков авторизации. Redirects и URL с username/password отклоняются. Используйте cookies/headers, не вставляйте bearer-реквизиты в URL плейлиста. scope — метка клиента, не серверная аутентификация и не криптографическая граница.

| videoProfiles | Preview target | Forward target while playing | Back target |
|---|---|---|---|
| preview | 4 s | 12 s | 8 s |
| playback | 4 s | 24 s | 12 s |

Оба профиля ограничивают ответ медиа 16 МиБ; ответ плейлиста — 1 МиБ. JSON подготовки ограничен 16 КиБ, клиентский срок — 190 секунд. Срок медиа-запроса — максимум 30 секунд. Эти лимиты не ограничивают всю память браузера и суммарно переданные байты. hls.js планирует и освобождает буферы. preload:none всё равно запрашивает manifest. Автоматических сетевых повторов нет.

Поля VideoState: phase, currentTime, duration, bufferedSeconds, receivedBytes, loadedSegments и необязательная error. bufferedSeconds — непрерывное окно впереди текущего времени, не вся выделенная память. loadedSegments считает разные URL буферизированных фрагментов сессии; receivedBytes — накопленные байты тела, полученные loader.

VideoPreparationError.outcome равен rejected для явных 400/401/403/404/413/415/422/429 и unknown для прочих статусов, потерянного/неверного ответа, transport errors и отмены после старта. Уже отменённый до вызова signal может бросить исходную причину отмены. Rejected означает отказ API, не общее доказательство отката хранилища; unknown не разрешает автоматический повтор.

[Browser source](../../browser/src/index.ts) · [Preparation transport](../../browser/src/prepare.ts) · [Complete example](../../examples/browser/player.ts)

## Среда и языковая база

Версия библиотеки 0.1.0 независима от версий Java/TypeScript. Java компилируется с release 17, использует records, стандартные Process/Path API и Reactor; LTS 21/25 и optional 27 не выбирают дополнительных функций. TypeScript 7.0.2 выпускает ES2022 ESM с декларациями типов. Среда требует fetch/streams, AbortController, HTMLVideoElement и поддержку hls.js/MediaSource и кодеков. Preview-флагов Java и native-HLS/whole-Blob fallback нет.

MIT распространяется на эту библиотеку. hls.js сохраняет исходную лицензию; внешние сборки FFmpeg и включённые кодеки имеют свои условия лицензирования/развёртывания. Сторонние notices не удаляются. Тесты и ограничения среды записаны в VERIFICATION, а не выводятся из target runtime.

[Guide](ru.md) · [Verification](../VERIFICATION.md) · [README](../../README.md)

Собственный fetch должен соблюдать переданный AbortSignal и правила redirect/credentials. Переопределение транспорта не передаёт библиотеке ответственность за авторизацию и ресурсы приложения.
