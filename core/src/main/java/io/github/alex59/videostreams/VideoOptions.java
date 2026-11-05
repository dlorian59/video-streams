package io.github.alex59.videostreams;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Preparation bounds, distinct from host CPU/disk quotas and browser playback buffers. */
public record VideoOptions(Path workDirectory, String ffmpegCommand, String ffprobeCommand,
                           Duration timeout, long maxInputBytes, long maxOutputBytes,
                           int maxSegments, int maxConcurrent) {
    public static final long DEFAULT_MAX_BYTES = 512L << 20;
    public static final long MAX_SEGMENT_BYTES = 16L << 20;
    public static final int MAX_SOURCE_BUFFER_BYTES = 1 << 20;
    public static final int MAX_PLAYLIST_BYTES = 256 << 10;

    public VideoOptions {
        workDirectory = Objects.requireNonNull(workDirectory, "workDirectory").toAbsolutePath().normalize();
        if (ffmpegCommand == null || ffmpegCommand.isBlank() || ffprobeCommand == null || ffprobeCommand.isBlank()) {
            throw new IllegalArgumentException("FFmpeg and ffprobe executable names are required");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0
                || maxInputBytes < 1 || maxOutputBytes < 1 || maxSegments < 1 || maxSegments > 10000
                || maxConcurrent < 1 || maxConcurrent > 64) {
            throw new IllegalArgumentException("Positive bounded limits required (deadline <=1h, segments <=10000, concurrency <=64)");
        }
    }

    public static VideoOptions defaults(Path workDirectory) {
        return new VideoOptions(workDirectory, "ffmpeg", "ffprobe", Duration.ofMinutes(3),
                DEFAULT_MAX_BYTES, DEFAULT_MAX_BYTES, 300, 2);
    }
}
