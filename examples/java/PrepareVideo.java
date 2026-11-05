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
