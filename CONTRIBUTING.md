# Development and verification / Разработка и проверка

[README](README.md) · [English guide](docs/0.1.0/en.md) · [Русское руководство](docs/0.1.0/ru.md) · [Verification](docs/VERIFICATION.md)

Use a full JDK 17+, Node.js 22.12+ for browser tooling, a POSIX work filesystem, and external ffmpeg/ffprobe with libx264/AAC support. The codecs/binaries are not bundled. Source directories are core, webflux and browser. / Нужны полный JDK 17+, Node.js 22.12+ для браузерных инструментов, рабочая файловая система POSIX и внешние ffmpeg/ffprobe с libx264/AAC. Кодеки и бинарные файлы не включены. Каталоги исходников: core, webflux и browser.

## Local Java artifacts / Локальные Java-артефакты

With sibling file-streams and video-streams checkouts, first prepare the exact file dependency in the repository-local artifact directory. / Для соседних file-streams и video-streams сначала подготовьте файловую зависимость в локальном каталоге артефактов:

```bash
cd ../file-streams
./gradlew publishToMavenLocal -Dmaven.repo.local="$PWD/build/local-repository"
cd ../video-streams
./gradlew build publishAllPublicationsToLocalRepository --no-daemon --max-workers=2
```

The build verifies the Java modules and compiles examples/java/PrepareVideo.java against the runtime classpath with release 17. It writes local Maven-format artifacts under build/local-repository. Configure that repository (and file-streams/build/local-repository) in your consumer; these commands do not publish to Maven Central. / Сборка проверяет Java-модули и компилирует examples/java/PrepareVideo.java с runtime classpath и release 17. Артефакты Maven записываются в build/local-repository. Настройте этот репозиторий и file-streams/build/local-repository в consumer; команды не публикуют в Maven Central.

Select another installed JDK through -PtestJavaVersion=17, 21 or 25; optional 27 does not change the release-17 API. The configured CI matrix and actual local runtime checks are distinct; see VERIFICATION. / Другой установленный JDK выбирается через -PtestJavaVersion=17, 21 или 25; optional 27 не меняет API release 17. Настроенная матрица CI и реальные локальные проверки различаются — см. VERIFICATION.

## Browser package / Браузерный пакет

From this checkout / Из рабочей копии:

```bash
cd browser
npm ci
npm test
npm pack
```

The tarball is a local package, not an npm publication. Use it in a separate consumer project with its existing bundler and browser test setup. The library emits ES2022 ESM plus declarations; the React integration is in the separate video-streams-react repository. / Tarball — локальный пакет, не публикация npm. Используйте его в отдельном consumer с существующим bundler и браузерными тестами. Библиотека выпускает ES2022 ESM и декларации; React-интеграция находится в отдельном video-streams-react.

From the repository root, after the browser package build / Из корня после сборки браузерного пакета:

```bash
browser/node_modules/.bin/tsc -p examples/browser/tsconfig.json
```

The reusable application helper is examples/browser/player.ts. It awaits an already-uploaded source preparation using the caller's session/CSRF, then attaches a scoped player. It supplies no storage mock, login bypass or automatic retry. / Переиспользуемый helper приложения — examples/browser/player.ts. Он ждёт подготовки загруженного источника с сессией/CSRF приложения и подключает scoped player. Storage mock, обхода входа и автоматического повтора нет.

## Secured Spring/React example / Защищённый пример Spring/React

Use the companion starter's examples/secured-video and the React repository's examples/spring-react. The server owns its isolated storage fixture, HTTPS configuration, session and CSRF setup. Follow that consumer's instructions rather than reusing production credentials. / Используйте examples/secured-video отдельного стартера и examples/spring-react React-репозитория. Сервер управляет своим изолированным хранилищем, HTTPS, сессией и CSRF. Следуйте инструкции consumer вместо использования рабочих реквизитов.

- [Server source](https://github.com/dlorian59/video-streams-spring-boot-starter/tree/main/examples/secured-video)
- [React source](https://github.com/dlorian59/video-streams-react/tree/main/examples/spring-react)

Those are publication destinations until the corresponding repositories exist remotely. Package registry publication is also pending. Local build/pack results must not be reported as a remote release. / До появления репозиториев это адреса будущей публикации. Публикация пакетов также ожидается. Локальные build/pack не следует выдавать за внешний релиз.

## License and deployment / Лицензия и развёртывание

Own code is MIT. Keep hls.js, Gradle Wrapper and other upstream notices. FFmpeg is an external dependency; use a build and codecs compatible with your deployment requirements. Resource byte limits are not a container CPU/memory quota or a hard filesystem quota. / Собственный код — MIT. Сохраняйте notices hls.js, Gradle Wrapper и других зависимостей. FFmpeg — внешний компонент; используйте подходящую вашей среде сборку и кодеки. Лимиты байтов не являются квотой CPU/памяти контейнера и жёсткой квотой файловой системы.
