package com.filemaster.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The FFmpeg options for every output format. No Spring code here, just lists of text,
 * so they are easy to read, change and test.
 *
 * Every argument is a separate list item and the command is started WITHOUT a shell,
 * so nothing a user uploads can be turned into a command.
 */
final class MediaCommands {

    private MediaCommands() {
    }

    /** x264 needs even width and height; this rounds odd sizes down by one pixel. */
    private static final String EVEN_SIZE = "scale=trunc(iw/2)*2:trunc(ih/2)*2";

    /** Skip subtitle and data tracks: many containers cannot hold them and conversion would fail. */
    private static final List<String> NO_EXTRA_TRACKS = List.of("-sn", "-dn");

    private static final List<String> H264_AAC = List.of(
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k");

    /** Audio and video outputs: the options for one target format such as "MP3" or "WEBM". */
    static List<String> argsFor(String target) {
        List<String> args = new ArrayList<>(NO_EXTRA_TRACKS);
        switch (target) {
            // ----- audio (the leading -vn drops any picture/video track) -----
            case "MP3" -> args.addAll(List.of("-vn", "-c:a", "libmp3lame", "-b:a", "192k"));
            case "WAV" -> args.addAll(List.of("-vn", "-c:a", "pcm_s16le"));
            case "M4A" -> args.addAll(List.of("-vn", "-c:a", "aac", "-b:a", "192k"));
            case "AAC" -> args.addAll(List.of("-vn", "-c:a", "aac", "-b:a", "192k", "-f", "adts"));
            case "OGG" -> args.addAll(List.of("-vn", "-c:a", "libvorbis", "-q:a", "5"));
            case "FLAC" -> args.addAll(List.of("-vn", "-c:a", "flac"));
            case "WMA" -> args.addAll(List.of("-vn", "-c:a", "wmav2", "-b:a", "192k", "-f", "asf"));
            // ----- video -----
            case "MP4", "MOV" -> {
                args.addAll(List.of("-vf", EVEN_SIZE));
                args.addAll(H264_AAC);
                args.addAll(List.of("-movflags", "+faststart"));
            }
            case "M4V" -> {   // ".m4v" is an MP4 file, but FFmpeg would treat the extension as raw video
                args.addAll(List.of("-vf", EVEN_SIZE));
                args.addAll(H264_AAC);
                args.addAll(List.of("-f", "mp4", "-movflags", "+faststart"));
            }
            case "MKV" -> {
                args.addAll(List.of("-vf", EVEN_SIZE));
                args.addAll(H264_AAC);
            }
            case "FLV" -> {
                args.addAll(List.of("-vf", EVEN_SIZE));
                args.addAll(H264_AAC);
                args.addAll(List.of("-ar", "44100", "-f", "flv"));
            }
            case "AVI" -> args.addAll(List.of("-vf", EVEN_SIZE, "-c:v", "mpeg4", "-q:v", "5",
                    "-c:a", "libmp3lame", "-b:a", "128k"));
            case "WEBM" -> args.addAll(List.of("-vf", EVEN_SIZE, "-c:v", "libvpx", "-crf", "10", "-b:v", "1M",
                    "-deadline", "realtime", "-cpu-used", "5", "-c:a", "libvorbis", "-q:a", "4"));
            case "WMV" -> args.addAll(List.of("-vf", EVEN_SIZE, "-c:v", "wmv2", "-b:v", "2M",
                    "-c:a", "wmav2", "-b:a", "128k", "-f", "asf"));
            default -> throw new IllegalArgumentException("Unknown target " + target);
        }
        return args;
    }

    /** PNG: reduce the picture to a smaller colour palette (like the "pngquant" tool does). */
    static List<String> pngArgs(int colors) {
        return List.of("-frames:v", "1",
                "-vf", "split[a][b];[a]palettegen=max_colors=" + colors + "[p];[b][p]paletteuse=dither=sierra2_4a",
                "-compression_level", "9", "-update", "1");
    }

    /** WebP: lossy re-encode at the given quality (0-100). */
    static List<String> webpArgs(int quality) {
        return List.of("-frames:v", "1", "-c:v", "libwebp", "-quality", String.valueOf(quality),
                "-compression_level", "4");
    }

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("MP3", "audio/mpeg"), Map.entry("WAV", "audio/wav"), Map.entry("M4A", "audio/mp4"),
            Map.entry("AAC", "audio/aac"), Map.entry("OGG", "audio/ogg"), Map.entry("FLAC", "audio/flac"),
            Map.entry("WMA", "audio/x-ms-wma"), Map.entry("MP4", "video/mp4"), Map.entry("MOV", "video/quicktime"),
            Map.entry("MKV", "video/x-matroska"), Map.entry("AVI", "video/x-msvideo"),
            Map.entry("WEBM", "video/webm"), Map.entry("WMV", "video/x-ms-wmv"),
            Map.entry("FLV", "video/x-flv"), Map.entry("M4V", "video/x-m4v"));

    static String mimeOf(String target) {
        return MIME_TYPES.getOrDefault(target, "application/octet-stream");
    }
}
