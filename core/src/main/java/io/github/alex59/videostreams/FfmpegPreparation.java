package io.github.alex59.videostreams;

import static io.github.alex59.videostreams.VideoFailure.Code.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fixed VOD profile and strict inventory validation; never executes user-supplied options or URLs. */
final class FfmpegPreparation {
    record Bundle(Path initialization, List<Path> segments, Path playlist, double duration) {}
    private static final long MAX_INPUT_PIXELS = 16777216;

    static Bundle prepare(Path source, Preparation attempt) throws Exception {
        String format = container(source);
        Path directory = source.getParent();
        Path probe = directory.resolve("probe.txt");
        var probeArgs = input(attempt.options.ffprobeCommand(), source, format);
        probeArgs.addAll(List.of("-show_entries", "format=duration:stream=codec_type,width,height",
                "-of", "default=noprint_wrappers=1"));
        attempt.run(probeArgs, directory, probe, null);
        double inputDuration = probe(Files.readAllLines(probe));
        if (inputDuration > attempt.options.maxSegments() * 2.0) {
            throw new VideoFailure(TOO_LARGE, "Video duration exceeds segment budget");
        }
        Files.delete(probe);
        Path output = Files.createDirectory(directory.resolve("vod"));
        var args = input(attempt.options.ffmpegCommand(), source, format);
        args.addAll(1, List.of("-nostdin", "-xerror"));
        args.addAll(List.of("-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-map_metadata", "-1",
                "-map_chapters", "-1", "-c:v", "libx264", "-preset", "veryfast", "-threads", "2",
                "-filter_threads", "1", "-vf",
                "scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-pix_fmt", "yuv420p", "-profile:v", "high", "-level:v", "3.1", "-r", "30", "-bf", "0",
                "-g", "60", "-keyint_min", "60", "-sc_threshold", "0", "-force_key_frames", "expr:gte(t,n_forced*2)",
                "-c:a", "aac", "-b:a", "128k", "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
                "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
                "-hls_flags", "independent_segments", "-hls_segment_filename", "segment-%06d.m4s", "playlist.m3u8"));
        attempt.run(args, output, null, output);
        Bundle bundle = inventory(output, attempt.options.maxSegments());
        // Duration comparison catches an apparently successful but incomplete output; no -t/-fs truncation is used.
        // Сверка длительности отклоняет незавершённый результат; ограничители -t/-fs не используются.
        if (Math.abs(bundle.duration() - inputDuration) > 1.0) {
            throw new VideoFailure(PROCESS_FAILED, "Output duration does not match the source");
        }
        return bundle;
    }

    private static String container(Path source) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(source)) { bytes = input.readNBytes(12); }
        if (bytes.length >= 12 && new String(bytes, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")) return "mov";
        if (bytes.length >= 4 && (bytes[0] & 255) == 0x1a && (bytes[1] & 255) == 0x45
                && (bytes[2] & 255) == 0xdf && (bytes[3] & 255) == 0xa3) return "matroska";
        throw new VideoFailure(INVALID_SOURCE, "Only MP4/MOV with ftyp and Matroska/WebM containers are accepted");
    }

    private static ArrayList<String> input(String executable, Path source, String format) {
        var args = new ArrayList<String>(List.of(executable, "-v", "error", "-max_alloc", "67108864",
                "-threads", "2", "-protocol_whitelist", "file", "-format_whitelist",
                format.equals("mov") ? "mov" : "matroska,webm", "-f", format));
        if (format.equals("mov")) args.addAll(List.of("-enable_drefs", "0", "-use_absolute_path", "0"));
        args.addAll(List.of("-probesize", "4194304", "-analyzeduration", "5000000", "-i", source.toString()));
        return args;
    }

    private static double probe(List<String> lines) {
        boolean video = false;
        int width = 0, height = 0;
        double duration = Double.NaN;
        try {
            for (String line : lines) {
                if (line.equals("codec_type=video")) video = true;
                if (line.startsWith("width=")) width = Math.max(width, Integer.parseInt(line.substring(6)));
                if (line.startsWith("height=")) height = Math.max(height, Integer.parseInt(line.substring(7)));
                if (line.startsWith("duration=")) duration = Double.parseDouble(line.substring(9));
            }
        } catch (NumberFormatException invalid) { throw new VideoFailure(INVALID_SOURCE, "Invalid media dimensions or duration"); }
        if (!video || width <= 0 || height <= 0 || (long) width * height > MAX_INPUT_PIXELS
                || !Double.isFinite(duration) || duration <= 0) {
            throw new VideoFailure(INVALID_SOURCE, "A bounded video stream and finite positive VOD duration are required");
        }
        return duration;
    }

    static Bundle inventory(Path output, int maxSegments) throws IOException {
        Path playlist = regular(output.resolve("playlist.m3u8"));
        if (Files.size(playlist) > VideoOptions.MAX_PLAYLIST_BYTES) throw new VideoFailure(TOO_LARGE, "Playlist exceeds limit");
        List<String> lines = Files.readAllLines(playlist, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals("#EXTM3U") || !lines.get(lines.size() - 1).equals("#EXT-X-ENDLIST")
                || lines.stream().filter(line -> line.equals("#EXT-X-MAP:URI=\"init.mp4\"")).count() != 1) {
            throw new VideoFailure(PROCESS_FAILED, "Incomplete VOD playlist");
        }
        List<Path> segments = new ArrayList<>();
        Double next = null;
        double duration = 0;
        for (String line : lines) {
            if (line.startsWith("#EXTINF:")) {
                if (next != null || !line.matches("#EXTINF:[0-9]+(?:\\.[0-9]+)?,")) throw invalidPlaylist();
                next = Double.parseDouble(line.substring(8, line.length() - 1));
                if (!Double.isFinite(next) || next <= 0 || next > 10) throw invalidPlaylist();
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String expected = String.format(Locale.ROOT, "segment-%06d.m4s", segments.size());
                if (next == null || !line.equals(expected) || segments.size() >= maxSegments) throw invalidPlaylist();
                segments.add(regular(output.resolve(expected)));
                duration += next;
                next = null;
            } else if (line.startsWith("#") && !(line.equals("#EXTM3U") || line.equals("#EXT-X-ENDLIST")
                    || line.equals("#EXT-X-MAP:URI=\"init.mp4\"") || line.equals("#EXT-X-INDEPENDENT-SEGMENTS")
                    || line.equals("#EXT-X-PLAYLIST-TYPE:VOD") || line.matches("#EXT-X-(VERSION|TARGETDURATION|MEDIA-SEQUENCE):[0-9]+"))) {
                throw invalidPlaylist();
            }
        }
        if (next != null || segments.isEmpty()) throw invalidPlaylist();
        try (var entries = Files.list(output)) {
            if (entries.count() != segments.size() + 2L) throw invalidPlaylist();
        }
        return new Bundle(regular(output.resolve("init.mp4")), List.copyOf(segments), playlist, duration);
    }

    private static VideoFailure invalidPlaylist() { return new VideoFailure(PROCESS_FAILED, "Invalid generated VOD inventory"); }
    private static Path regular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0) throw invalidPlaylist();
        return path;
    }
}
