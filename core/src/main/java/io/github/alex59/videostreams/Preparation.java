package io.github.alex59.videostreams;

import static io.github.alex59.videostreams.VideoFailure.Code.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** One attempt's cancellation, deadline, process and private disk lifetime. */
final class Preparation implements AutoCloseable {
    private static final long PROBE_LIMIT = 65536;
    final VideoOptions options;
    private final long started = System.nanoTime();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Thread worker;
    private volatile Process process;
    private Path directory;

    Preparation(VideoOptions options) { this.options = options; }

    Path open() throws IOException {
        worker = Thread.currentThread();
        check();
        Files.createDirectories(options.workDirectory());
        directory = Files.createTempDirectory(options.workDirectory(), "prepare-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        return directory;
    }

    void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new VideoFailure(CANCELLED, "Video preparation cancelled");
        }
        if (System.nanoTime() - started >= options.timeout().toNanos()) {
            throw new VideoFailure(TIMEOUT, "Video preparation deadline exceeded");
        }
    }

    Duration remaining() {
        check();
        return Duration.ofNanos(Math.max(1, options.timeout().toNanos() - (System.nanoTime() - started)));
    }

    boolean ownedByCurrentThread() { return worker == Thread.currentThread(); }
    void finished() { finished.countDown(); }
    boolean awaitFinished(long nanos) throws InterruptedException {
        return finished.await(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    void cancel() {
        cancelled.set(true);
        Thread current = worker;
        if (current != null) current.interrupt();

    }

    void run(List<String> command, Path working, Path stdout, Path inventory) throws Exception {
        check();
        var builder = new ProcessBuilder(command).directory(working.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        if (stdout == null) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        else builder.redirectOutput(stdout.toFile());
        Process running = builder.start();
        process = running;
        try {
            running.getOutputStream().close();
            do {
                check();
                if (stdout != null && Files.exists(stdout) && Files.size(stdout) > PROBE_LIMIT) {
                    throw new VideoFailure(INVALID_SOURCE, "Probe response exceeds limit");
                }
                if (inventory != null) checkOutput(inventory);
            } while (!running.waitFor(100, TimeUnit.MILLISECONDS));
            check();
            if (stdout != null && Files.size(stdout) > PROBE_LIMIT) {
                throw new VideoFailure(INVALID_SOURCE, "Probe response exceeds limit");
            }
            if (inventory != null) checkOutput(inventory);
            if (running.exitValue() != 0) throw new VideoFailure(PROCESS_FAILED, "Media process rejected input");
        } finally {
            terminate(running);
            process = null;
        }
    }

    void checkOutput(Path output) throws IOException {
        long total = 0;
        int count = 0;
        try (var paths = Files.list(output)) {
            var entries = paths.iterator();
            while (entries.hasNext()) {
                Path file = entries.next();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new VideoFailure(PROCESS_FAILED, "Unexpected output file type");
                }
                String name = file.getFileName().toString();
                long size = Files.size(file);
                total = Math.addExact(total, size);
                long fileLimit = name.endsWith(".m3u8") ? VideoOptions.MAX_PLAYLIST_BYTES : VideoOptions.MAX_SEGMENT_BYTES;
                if (++count > options.maxSegments() + 2 || total > options.maxOutputBytes() || size > fileLimit) {
                    throw new VideoFailure(TOO_LARGE, "Video output exceeds configured bounds");
                }
            }
        }
    }

    private static void terminate(Process running) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            var children = running.descendants().toList();
            children.forEach(ProcessHandle::destroyForcibly);
            // Let a wrapper reap its children before killing the wrapper itself.
            // Даём обёртке убрать завершённых потомков до остановки самой обёртки.
            long reapDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            while (children.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < reapDeadline) {
                try { TimeUnit.MILLISECONDS.sleep(10); }
                catch (InterruptedException interruption) { interrupted = true; }
            }
            if (running.isAlive()) running.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (running.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) {
                try {
                    if (running.isAlive()) running.waitFor(50, TimeUnit.MILLISECONDS);
                    else TimeUnit.MILLISECONDS.sleep(50);
                } catch (InterruptedException interruption) { interrupted = true; }
                if (System.nanoTime() >= deadline) throw new IOException("Media process did not terminate");
            }
            if (children.stream().anyMatch(ProcessHandle::isAlive)) throw new IOException("Media child did not terminate");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() throws IOException {
        Process running = process;
        if (running != null) terminate(running);
        if (directory != null) {
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
