package io.github.alex59.videostreams;

import java.util.Objects;
import java.util.UUID;

/** One immutable generation. Source-key authorization includes its historical generations unless the host narrows it. */
public record VideoAsset(UUID generation, String sourceKey, String playlistKey, String mimeType,
                         double durationSeconds, int segmentCount) {
    public VideoAsset {
        Objects.requireNonNull(generation, "generation");
        if (!Objects.equals(playlistKey, VideoStreams.assetKey(sourceKey, generation, "playlist.m3u8"))
                || !"application/vnd.apple.mpegurl".equals(mimeType)
                || !Double.isFinite(durationSeconds) || durationSeconds <= 0 || segmentCount < 1) {
            throw new IllegalArgumentException("Invalid video generation metadata");
        }
    }
    public String assetKey(String name) { return VideoStreams.assetKey(sourceKey, generation, name); }
}
