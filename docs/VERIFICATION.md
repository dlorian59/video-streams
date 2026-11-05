# Verification / Проверка — 0.1.0

[README](../README.md) · [EN guide](0.1.0/en.md) · [Руководство RU](0.1.0/ru.md) · [CONTRIBUTING](../CONTRIBUTING.md)

## Java preparation and HTTP adapter

Local verification used Oracle JDK 25.0.1 and Temurin JDK 17 with FFmpeg 6.1.1-3ubuntu5. The Java build targets release 17 (class major 61; JVM metadata 17).

| Check | Evidence and boundary |
|---|---|
| Core | Nine core tests passed in the final Java 17 build, including actual MP4 and WebM preparation, rejected input/output budgets, deadline/cancellation, descendant cleanup, active-process shutdown and injected final-playlist failure. |
| WebFlux | One adapter test passed. This is adapter verification, not a production security audit. |
| Real output | A 4.2-second source produced three media segments with total duration 4.2 seconds. A real 1.2-second WebM source produced one fMP4 segment. |
| Resource cancellation | A controlled executable/child-process fixture verified both processes terminated, scratch disappeared and no artifacts were published. Successful FFmpeg and budget-rejection paths used real FFmpeg. A shutdown test verified the active child PID was dead and scratch removed when close() returned. |
| Publication failure | Failure injected before the final playlist committed left four prior parts and no playlist. This does not test or disprove an unknown successful commit after a lost response. |
| Documentation Java source | :core:compileExamples compiled examples/java/PrepareVideo.java against the real runtime classpath with release 17 on both tested JDKs. |
| Artifacts | Core/webflux build and repository-local Maven publication passed; no Maven Central publication is claimed. |

Commands used for the Java checks:

```bash
./gradlew build publishAllPublicationsToLocalRepository --max-workers=2 --no-daemon
./gradlew build --max-workers=2 --no-daemon --warning-mode all
```

The initial runs used JDK 25 and JDK 17. The final lifecycle/WebM expansion was checked on Java 17 with build + publishAllPublicationsToLocalRepository. Logs are local build-final.log, build-java17.log and final build-shutdown.log; test XML lives in core/build/test-results/test and webflux/build/test-results/test. The logs are development artifacts, not committed fixtures.

## Browser and full-stack status

Browser transport: five tests passed, covering bounded reads, cancellation, request lifetime and the zero-offset HLS Range regression. React: two focused tests, strict example compilation and packed consumers on React 18.3.1/19.3.1 including SSR passed. The standalone TypeScript example compiled against the generated declarations.

Headless Chromium 156.0.8078.4 with Playwright 1.64.1 played real FFmpeg H.264/AAC fMP4 output. The local HTTP fixture contained 24 seconds / 12 segments: preview requested 3 segments (6 seconds for a 4-second target), playback advanced, seek to 18 seconds loaded the target region, and scope change/unmount stopped obsolete requests. These checks ran in EN/RU.

The complete EN/RU React → Spring Boot 4.1.2 WebFlux → Garage 2.4.1 S3 scenario used HTTP/2 over TLS and locally built Java artifacts. A 7,374,595-byte source uploaded with 201, preparation returned 201, and FFmpeg produced a 32-second VOD with 16 segments. Preview requested 3/16 before playback; playback and seek to 24 seconds succeeded. Closing the player stopped requests, clearing the session made playlist access return 401, and both browser runs had zero page errors. The Spring consumer additionally checked foreign source/artifact 403, missing CSRF 403, mismatched prepare body 400, disallowed artifact 400, range 206 and absence of the generic /objects route (404).

[React visual evidence and reproduction](https://github.com/dlorian59/video-streams-react/blob/main/CONTRIBUTING.md#verification) · [Secured Spring consumer](https://github.com/dlorian59/video-streams-spring-boot-starter/tree/main/examples/secured-video).

This is a synthetic local end-to-end scenario, not a throughput, HA or all-browser benchmark. No native-HLS/whole-Blob fallback is implemented.
## Проверка Java

Локальные сборки выполнены на Oracle JDK 25.0.1 и Temurin JDK 17 с FFmpeg 6.1.1-3ubuntu5. В финальной сборке Java 17 прошли девять тестов core и один WebFlux; байткод и metadata сохраняют Java 17. Реальный FFmpeg получил из 4,2 секунды источника три сегмента общей длительностью 4,2 секунды. Реальный источник WebM длительностью 1,2 секунды преобразован в один fMP4-сегмент. Контролируемый процесс с дочерним процессом проверил отмену и очистку; после close() проверены остановка активного PID и отсутствие scratch. Финальные проверки WebM/close выполнены на Java 17; первоначальная Java 25 матрица предшествовала этим дополнениям. Ошибка до фиксации финального плейлиста оставила четыре ранее записанных части без плейлиста: это не проверка неизвестного успешного commit при потере ответа.

Полный Java-пример скомпилирован через :core:compileExamples на обоих JDK с реальным runtime classpath. Локальная публикация Maven успешна; внешний релиз не заявляется. Отдельный examples/browser/player.ts прошёл строгую проверку TypeScript с собранными декларациями browser: проверены реальные prepare/attach API и параметры scope/отмены. Результаты runtime-браузера и защищённого Spring/React-сценария записываются отдельно.

## Limits / Ограничения

No throughput/HA benchmark, hard OS disk/memory quota, all-browser/codec matrix, remote CI execution or registry publication is claimed. Periodic output checks can overshoot before termination. A cancelled prepare can have uploaded objects already; a final playlist commit can be uncertain. Host authorization, external FFmpeg lifecycle/patching, storage retention and application persistence remain deployment responsibilities.

Не заявляются нагрузочный/HA-бенчмарк, жёсткая квота диска/памяти ОС, полная матрица браузеров/кодеков, внешний прогон CI и публикация в реестр. Периодический контроль выхода допускает превышение до остановки. У отменённой подготовки могут остаться загруженные объекты; фиксация финального плейлиста может быть неизвестна. Права доступа, внешняя сборка FFmpeg и её обновления, очистка хранилища и сохранение состояния остаются ответственностью приложения.
