package io.github.alex59.videostreams;

import static io.github.alex59.videostreams.VideoFailure.Code.*;
import io.github.alex59.filestreams.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepares immutable HLS generations; the caller retains ownership of the supplied storage. */
public final class VideoStreams implements AutoCloseable {
    private final ObjectStreams storage;
    private final VideoOptions options;
    private final Semaphore slots;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Preparation> active = ConcurrentHashMap.newKeySet();

    public VideoStreams(ObjectStreams storage, VideoOptions options) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.options = Objects.requireNonNull(options, "options");
        this.slots = new Semaphore(options.maxConcurrent());
    }

    /** Cold, non-retrying operation. Cancellation releases local resources, not already committed storage objects. */
    public Mono<VideoAsset> prepare(String sourceKey) {
        return Mono.defer(() -> {
            UploadRequest.unknownLength(sourceKey);
            if (closed.get()) return Mono.error(new VideoFailure(CLOSED, "Video service is closed"));
            if (!slots.tryAcquire()) return Mono.error(new VideoFailure(BUSY, "Video preparation capacity exhausted"));
            return Mono.create(sink -> {
                Preparation attempt = new Preparation(options);
                active.add(attempt);
                sink.onCancel(attempt::cancel);
                if (closed.get()) attempt.cancel();
                Thread worker = new Thread(() -> {
                    VideoAsset result = null;
                    Throwable failure = null;
                    boolean cleaned = false;
                    try {
                        Path directory = attempt.open();
                        Path source = directory.resolve("source.media");
                        download(sourceKey, source, attempt);
                        var bundle = FfmpegPreparation.prepare(source, attempt);
                        UUID generation = UUID.randomUUID();
                        publish(sourceKey, generation, bundle, attempt);
                        result = new VideoAsset(generation, sourceKey, assetKey(sourceKey, generation, "playlist.m3u8"),
                                "application/vnd.apple.mpegurl", bundle.duration(), bundle.segments().size());
                    } catch (Throwable error) {
                        failure = translate(error, attempt);
                    } finally {
                        // A failed cleanup quarantines this slot instead of admitting more work over live resources.
                        // Неудачная очистка блокирует слот: новые задания не накладываются на оставшиеся ресурсы.
                        try { attempt.close(); cleaned = true; }
                        catch (Throwable cleanup) {
                            if (failure == null) failure = new VideoFailure(PROCESS_FAILED, "Media cleanup failed", cleanup);
                            else failure.addSuppressed(cleanup);
                        }
                        if (cleaned) { active.remove(attempt); slots.release(); }
                        attempt.finished();
                    }
                    if (failure == null) sink.success(result);
                    else sink.error(failure);
                }, "video-streams-prepare");
                worker.setDaemon(true);
                try { worker.start(); }
                catch (Throwable error) { active.remove(attempt); slots.release(); attempt.finished(); sink.error(error); }
            });
        });
    }

    private void download(String key, Path target, Preparation attempt) throws IOException {
        try (ObjectDownload object = Objects.requireNonNull(storage.download(key).block(attempt.remaining()), "download");
             FileChannel file = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (object.length() <= 0) throw new VideoFailure(INVALID_SOURCE, "Video source is empty");
            if (object.length() > options.maxInputBytes()) throw new VideoFailure(TOO_LARGE, "Video source exceeds input limit");
            var checked = new CheckedStream(object.body(), OptionalLong.of(object.length()),
                    new StreamLimits(options.maxInputBytes(), VideoOptions.MAX_SOURCE_BUFFER_BYTES));
            for (ByteBuffer buffer : checked.content().takeUntilOther(Mono.delay(attempt.remaining())
                    .then(Mono.error(new VideoFailure(TIMEOUT, "Source download exceeded deadline")))).toIterable(1)) {
                attempt.check();
                while (buffer.hasRemaining()) { attempt.check(); file.write(buffer); }
            }
            attempt.check();
        } catch (VideoFailure error) { throw error; }
        catch (Exception error) { attempt.check(); throw new VideoFailure(STORAGE_FAILED, "Cannot read video source", error); }
    }

    private void publish(String source, UUID generation, FfmpegPreparation.Bundle bundle, Preparation attempt) {
        upload(assetKey(source, generation, "init.mp4"), bundle.initialization(), "video/mp4", attempt);
        for (Path segment : bundle.segments()) {
            upload(assetKey(source, generation, segment.getFileName().toString()), segment, "video/mp4", attempt);
        }
        // The playlist is the final publication boundary; prior objects can remain orphaned after failure.
        // Плейлист публикуется последним; ранее записанные объекты могут остаться после ошибки.
        upload(assetKey(source, generation, "playlist.m3u8"), bundle.playlist(), "application/vnd.apple.mpegurl", attempt);
    }

    private void upload(String key, Path file, String type, Preparation attempt) {
        attempt.check();
        try {
            long length = Files.size(file);
            Flux<ByteBuffer> content = Flux.using(() -> FileChannel.open(file), channel ->
                    Flux.<ByteBuffer>generate(sink -> {
                        try {
                            attempt.check();
                            ByteBuffer buffer = ByteBuffer.allocate(65536);
                            int count = channel.read(buffer);
                            if (count < 0) sink.complete();
                            else { buffer.flip(); sink.next(buffer); }
                        } catch (Exception error) { sink.error(error); }
                    }), channel -> {
                        try { channel.close(); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                    }).subscribeOn(Schedulers.boundedElastic());
            UploadResult result = storage.upload(UploadRequest.knownLength(key, length, type), content)
                    .block(attempt.remaining());
            if (result == null || !key.equals(result.key()) || result.bytes() != length) {
                throw new VideoFailure(STORAGE_FAILED, "Storage returned an unexpected commit");
            }
            attempt.check();
        } catch (VideoFailure error) { throw error; }
        catch (Exception error) { attempt.check(); throw new VideoFailure(STORAGE_FAILED, "Cannot publish video artifact", error); }
    }

    /** Maps only generated, allowlisted names. The host must authorize the source and generation first. */
    public static String assetKey(String sourceKey, UUID generation, String name) {
        UploadRequest.unknownLength(sourceKey);
        Objects.requireNonNull(generation, "generation");
        if (name == null || !(name.equals("playlist.m3u8") || name.equals("init.mp4")
                || name.matches("segment-[0-9]{6}\\.m4s"))) throw new IllegalArgumentException("Invalid video artifact name");
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sourceKey.getBytes(StandardCharsets.UTF_8)));
            return "video-streams/" + digest + "/" + generation + "/" + name;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Throwable translate(Throwable error, Preparation attempt) {
        if (error instanceof VideoFailure) return error;
        try { attempt.check(); }
        catch (VideoFailure state) { return state; }
        return new VideoFailure(PROCESS_FAILED, "Video preparation failed", error);
    }

    /**
     * Cancels owned attempts and waits up to six seconds for cleanup. The caller-owned storage is never closed.
     * Throws if shutdown cannot finish; an owning worker cannot wait for itself and completes cleanup on return.
     */
    @Override public void close() {
        closed.set(true);
        var attempts = java.util.List.copyOf(active);
        attempts.forEach(Preparation::cancel);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        for (Preparation attempt : attempts) {
            if (attempt.ownedByCurrentThread()) continue;
            try {
                if (!attempt.awaitFinished(deadline - System.nanoTime()) || active.contains(attempt)) {
                    throw new IllegalStateException("Video preparation cleanup did not complete during shutdown");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for video preparation cleanup", interruption);
            }
        }
    }
}
