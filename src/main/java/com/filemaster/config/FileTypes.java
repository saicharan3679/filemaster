package com.filemaster.config;

import java.util.List;
import java.util.Set;

/**
 * One place that lists which file extensions each feature accepts.
 * Only extensions on these allow-lists are ever processed.
 */
public final class FileTypes {

    private FileTypes() {
    }

    public static final Set<String> IMAGE = Set.of("jpg", "jpeg", "png");

    public static final Set<String> TEXT = Set.of(
            "txt", "csv", "json", "xml", "html", "css", "js", "md", "log");

    public static final Set<String> DOCUMENT = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx");

    /** Everything the compressor accepts (images are shrunk, the rest are zipped). */
    public static final Set<String> COMPRESSIBLE = union(IMAGE, TEXT, DOCUMENT);

    /** Everything the converter accepts as input. */
    public static final Set<String> CONVERTIBLE = Set.of("jpg", "jpeg", "png", "txt", "docx", "doc", "pdf");

    /** Audio formats the Audio Converter accepts. */
    public static final Set<String> AUDIO = Set.of("mp3", "wav", "m4a", "aac", "ogg", "flac", "wma");

    /** Video formats the Video Converter accepts. */
    public static final Set<String> VIDEO = Set.of("mp4", "mov", "mkv", "avi", "webm", "wmv", "flv", "m4v");

    /** Formats the Image Compressor accepts (WebP is handled with FFmpeg). */
    public static final Set<String> IMAGE_FORMATS = Set.of("jpg", "jpeg", "png", "webp");

    /** Sorted list used in friendly error messages. */
    public static List<String> sorted(Set<String> extensions) {
        return extensions.stream().sorted().toList();
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        java.util.HashSet<String> all = new java.util.HashSet<>();
        for (Set<String> s : sets) {
            all.addAll(s);
        }
        return Set.copyOf(all);
    }
}
