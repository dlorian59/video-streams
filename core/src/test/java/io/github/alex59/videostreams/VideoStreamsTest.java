package io.github.alex59.videostreams;

import io.github.alex59.filestreams.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class VideoStreamsTest {
    static Path root;
    static byte[] source;
    @BeforeAll static void fixture() throws Exception {
        root = Files.createTempDirectory(Files.createDirectories(Path.of("build").toAbsolutePath()), "video-test-");
        Path video = root.resolve("fixture.mp4");
        Process process = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                "testsrc2=size=320x180:rate=30", "-t", "4.2", "-c:v", "libx264", "-threads", "1",
                "-pix_fmt", "yuv420p", video.toString()).inheritIO().start();
        assertEquals(0, process.waitFor());
        source = Files.readAllBytes(video);
    }
    @AfterAll static void cleanup() throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private VideoOptions options(String name) { return VideoOptions.defaults(root.resolve(name)); }
    private VideoFailure failed(VideoStreams service) {
        return assertThrows(VideoFailure.class, () -> service.prepare("alice:clip").block(Duration.ofSeconds(20)));
    }
    @Test void realFfmpegProducesCompleteImmutablePlaylistLastAndPreservesStorageOwnership() throws Exception {
        MemoryStorage storage = new MemoryStorage(source);
        VideoOptions options = options("success");
        try (VideoStreams service = new VideoStreams(storage, options)) {
            VideoAsset result = service.prepare("alice:clip").block(Duration.ofSeconds(20));
            assertNotNull(result);
            assertEquals(3, result.segmentCount());
            assertEquals(4.2, result.durationSeconds(), .04);
            assertEquals(result.playlistKey(), storage.writes.get(storage.writes.size() - 1));
            String playlist = new String(storage.objects.get(result.playlistKey()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(playlist.endsWith("#EXT-X-ENDLIST\n"));
            assertTrue(playlist.contains("#EXT-X-MAP:URI=\"init.mp4\""));
            assertEquals(5, storage.writes.size());
            assertEquals(1, storage.downloads.get());
            assertEquals(1, storage.closedDownloads.get());
            assertArrayEquals(source, storage.objects.get("alice:clip"));
            assertEmpty(options.workDirectory());
        }
        try (ObjectDownload download = storage.download("alice:clip").block()) { assertNotNull(download); }
    }
    @Test void acceptsWebmWithoutTrustingSourceKeyExtension() throws Exception {
        Path video = root.resolve("fixture.webm");
        Process process = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                "testsrc2=size=160x90:rate=30", "-t", "1.2", "-c:v", "libvpx", "-threads", "1",
                video.toString()).inheritIO().start();
        assertEquals(0, process.waitFor());
        MemoryStorage storage = new MemoryStorage(Files.readAllBytes(video));
        try (VideoStreams service = new VideoStreams(storage, options("webm"))) {
            VideoAsset asset = service.prepare("alice:clip").block(Duration.ofSeconds(20));
            assertNotNull(asset);
            assertEquals(1, asset.segmentCount());
            assertEquals(1.2, asset.durationSeconds(), .05);
        }
    }
    @Test void failedPartCommitNeverPublishesPlaylistAndCleansScratch() throws Exception {
        MemoryStorage storage = new MemoryStorage(source);
        storage.failSuffix = "segment-000001.m4s";
        VideoOptions options = options("part-failure");
        try (VideoStreams service = new VideoStreams(storage, options)) {
            assertEquals(VideoFailure.Code.STORAGE_FAILED, failed(service).code());
            assertTrue(storage.writes.stream().noneMatch(key -> key.endsWith("playlist.m3u8")));
            assertTrue(storage.writes.size() > 0);
            assertEmpty(options.workDirectory());
        }
    }
    @Test void failedFinalCommitLeavesOnlyUnpublishedImmutableParts() throws Exception {
        MemoryStorage storage = new MemoryStorage(source);
        storage.failSuffix = "playlist.m3u8";
        VideoOptions options = options("playlist-failure");
        try (VideoStreams service = new VideoStreams(storage, options)) {
            assertEquals(VideoFailure.Code.STORAGE_FAILED, failed(service).code());
            assertEquals(4, storage.writes.size());
            assertTrue(storage.writes.stream().noneMatch(key -> key.endsWith("playlist.m3u8")));
            assertEmpty(options.workDirectory());
        }
    }
    @Test void rejectsPlaylistSourceAndInputOutputBudgets() throws Exception {
        VideoOptions options = options("invalid");
        try (VideoStreams service = new VideoStreams(new MemoryStorage("#EXTM3U\nhttp://localhost/secret".getBytes()), options)) {
            assertEquals(VideoFailure.Code.INVALID_SOURCE, failed(service).code());
        }
        var tiny = new VideoOptions(options.workDirectory(), "ffmpeg", "ffprobe", Duration.ofSeconds(15), 32, 1024, 3, 1);
        try (VideoStreams service = new VideoStreams(new MemoryStorage(source), tiny)) {
            assertEquals(VideoFailure.Code.TOO_LARGE, failed(service).code());
        }
        var output = new VideoOptions(options.workDirectory(), "ffmpeg", "ffprobe", Duration.ofSeconds(15), source.length, 1024, 3, 1);
        MemoryStorage storage = new MemoryStorage(source);
        try (VideoStreams service = new VideoStreams(storage, output)) {
            assertEquals(VideoFailure.Code.TOO_LARGE, failed(service).code());
            assertTrue(storage.writes.isEmpty());
        }
        assertEmpty(options.workDirectory());
    }
    @Test void cancellationTerminatesOwnedProcessBeforeCapacityIsReusable() throws Exception {
        Path script = root.resolve("slow-ffmpeg");
        Path pid = root.resolve("slow.pid");
        Path childPid = root.resolve("child.pid");
        Files.writeString(script, "#!/bin/sh\necho $$ > '" + pid + "'\nsleep 60 &\necho $! > '" + childPid + "'\nwait\n");
        script.toFile().setExecutable(true);
        var options = new VideoOptions(root.resolve("cancel"), script.toString(), "ffprobe", Duration.ofSeconds(15),
                VideoOptions.DEFAULT_MAX_BYTES, VideoOptions.DEFAULT_MAX_BYTES, 300, 1);
        MemoryStorage storage = new MemoryStorage(source);
        try (VideoStreams service = new VideoStreams(storage, options)) {
            var subscription = service.prepare("alice:clip").subscribe();
            await(() -> Files.exists(childPid));
            long processId = Long.parseLong(Files.readString(pid).trim());
            long childId = Long.parseLong(Files.readString(childPid).trim());
            assertEquals(VideoFailure.Code.BUSY, failed(service).code());
            subscription.dispose();
            await(() -> ProcessHandle.of(processId).map(handle -> !handle.isAlive()).orElse(true));
            await(() -> ProcessHandle.of(childId).map(handle -> !handle.isAlive()).orElse(true));
            await(() -> empty(options.workDirectory()));
            assertTrue(storage.writes.isEmpty());
            service.close();
            assertEquals(VideoFailure.Code.CLOSED, failed(service).code());
        }
    }
    @Test void closeWaitsForOwnedProcessAndScratchCleanup() throws Exception {
        Path script = root.resolve("shutdown-ffmpeg");
        Path pid = root.resolve("shutdown.pid");
        Files.writeString(script, "#!/bin/sh\necho $$ > '" + pid + "'\nexec sleep 60\n");
        script.toFile().setExecutable(true);
        var options = new VideoOptions(root.resolve("shutdown"), script.toString(), "ffprobe", Duration.ofSeconds(15),
                VideoOptions.DEFAULT_MAX_BYTES, VideoOptions.DEFAULT_MAX_BYTES, 300, 1);
        var service = new VideoStreams(new MemoryStorage(source), options);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        service.prepare("alice:clip").subscribe(ignored -> fail("Unexpected commit"), error::set);
        await(() -> Files.exists(pid));
        long processId = Long.parseLong(Files.readString(pid).trim());
        service.close();
        assertFalse(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false));
        assertEmpty(options.workDirectory());
        await(() -> error.get() != null);
        assertEquals(VideoFailure.Code.CANCELLED, ((VideoFailure) error.get()).code());
        service.close();
    }
    @Test void stalledSourceHasWholeOperationDeadline() throws Exception {
        MemoryStorage storage = new MemoryStorage(source) {
            @Override public Mono<ObjectDownload> download(String key, String range) { return Mono.just(new ObjectDownload(
                    1, "video/mp4", "fixture", null, Flux.never(), closedDownloads::incrementAndGet)); }
        };
        var options = new VideoOptions(root.resolve("deadline"), "ffmpeg", "ffprobe", Duration.ofMillis(150), 100, 100, 1, 1);
        try (VideoStreams service = new VideoStreams(storage, options)) {
            assertEquals(VideoFailure.Code.TIMEOUT, failed(service).code());
            assertEquals(1, storage.closedDownloads.get());
            assertEmpty(options.workDirectory());
        }
    }
    @Test void generatedNamesCannotEscapeNamespace() {
        UUID generation = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> VideoStreams.assetKey("alice:clip", generation, "../secret"));
        assertThrows(IllegalArgumentException.class, () -> VideoStreams.assetKey("alice:clip", generation, "https://elsewhere/x"));
        assertNotEquals(VideoStreams.assetKey("alice:clip", generation, "init.mp4"),
                VideoStreams.assetKey("bob:clip", generation, "init.mp4"));
    }
    static void assertEmpty(Path path) throws Exception { assertTrue(empty(path), "Scratch files were not cleaned"); }
    static boolean empty(Path path) throws Exception {
        try (var paths = Files.list(path)) { return paths.findAny().isEmpty(); }
    }
    interface Condition { boolean get() throws Exception; }
    static void await(Condition condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.get()) { if (System.nanoTime() >= end) fail("Condition timed out"); Thread.sleep(20); }
    }
    static class MemoryStorage implements ObjectStreams {
        final Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> writes = new CopyOnWriteArrayList<>();
        final AtomicInteger downloads = new AtomicInteger(), closedDownloads = new AtomicInteger();
        String failSuffix;
        MemoryStorage(byte[] source) { objects.put("alice:clip", source); }
        @Override public Mono<ObjectDownload> download(String key, String range) {
            return Mono.fromSupplier(() -> {
                downloads.incrementAndGet();
                byte[] bytes = Objects.requireNonNull(objects.get(key));
                return new ObjectDownload(bytes.length, "video/mp4", "fixture", null,
                        Flux.just(ByteBuffer.wrap(bytes)), closedDownloads::incrementAndGet);
            });
        }
        @Override public Mono<UploadResult> upload(UploadRequest request, Publisher<ByteBuffer> body) {
            if (failSuffix != null && request.key().endsWith(failSuffix)) return Mono.error(new IllegalStateException("fixture failure"));
            return Flux.from(body).reduce(new ByteArrayOutputStream(), (out, buffer) -> {
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); out.writeBytes(bytes); return out;
            }).map(out -> {
                objects.put(request.key(), out.toByteArray()); writes.add(request.key());
                return new UploadResult(true, request.key(), out.size(), "fixture");
            });
        }
    }
}
