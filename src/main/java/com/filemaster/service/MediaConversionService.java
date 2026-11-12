package com.filemaster.service;

import com.filemaster.config.FileTypes;
import com.filemaster.exception.FileMasterException;
import com.filemaster.model.FileResult;
import com.filemaster.model.FormatsResponse;
import com.filemaster.model.UploadInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Audio and video conversion with FFmpeg (real re-encoding, never a renamed extension).
 *
 *  - audio files   -> any other audio format
 *  - video files   -> any other video format
 *  - MP4, MOV, MKV and WEBM videos can also be turned into MP3, WAV or M4A (audio extraction)
 */
@Service
public class MediaConversionService {

    private static final List<String> AUDIO_FORMATS = List.of("MP3", "WAV", "M4A", "AAC", "OGG", "FLAC", "WMA");
    private static final List<String> VIDEO_FORMATS = List.of("MP4", "MOV", "MKV", "AVI", "WEBM", "WMV", "FLV", "M4V");
    private static final Set<String> EXTRACT_FROM = Set.of("MP4", "MOV", "MKV", "WEBM");
    private static final List<String> EXTRACT_TO = List.of("MP3", "WAV", "M4A");

    private static final Map<String, List<String>> AUDIO_CONVERSIONS = audioConversions();
    private static final Map<String, List<String>> VIDEO_CONVERSIONS = videoConversions();

    private final FileValidationService validator;
    private final TempFileService tempFiles;
    private final FfmpegService ffmpeg;
    private final long maxMediaMb;

    public MediaConversionService(FileValidationService validator,
                                  TempFileService tempFiles,
                                  FfmpegService ffmpeg,
                                  @Value("${filemaster.max-media-size-mb:50}") long maxMediaMb) {
        this.validator = validator;
        this.tempFiles = tempFiles;
        this.ffmpeg = ffmpeg;
        this.maxMediaMb = maxMediaMb;
    }

    // ---------- what is supported (the frontend reads this) ----------

    public FormatsResponse audioFormats() {
        return new FormatsResponse(extensionsOf(AUDIO_FORMATS), AUDIO_CONVERSIONS, List.of());
    }

    public FormatsResponse videoFormats() {
        return new FormatsResponse(extensionsOf(VIDEO_FORMATS), VIDEO_CONVERSIONS, List.of());
    }

    // ---------- conversion ----------

    public FileResult convertAudio(MultipartFile file, String targetFormat) {
        return convert(file, targetFormat, FileTypes.AUDIO, AUDIO_CONVERSIONS);
    }

    public FileResult convertVideo(MultipartFile file, String targetFormat) {
        return convert(file, targetFormat, FileTypes.VIDEO, VIDEO_CONVERSIONS);
    }

    private FileResult convert(MultipartFile file, String targetFormat,
                               Set<String> allowedExtensions, Map<String, List<String>> conversions) {
        UploadInfo info = validator.validate(file, allowedExtensions, maxMediaMb);
        String source = info.extension().toUpperCase(Locale.ROOT);
        String target = targetFormat == null ? "" : targetFormat.trim().toUpperCase(Locale.ROOT);

        if (!conversions.getOrDefault(source, List.of()).contains(target)) {
            throw FileMasterException.badRequest("Converting " + source + " to "
                    + (target.isEmpty() ? "that format" : target) + " is not supported.");
        }
        String targetExtension = target.toLowerCase(Locale.ROOT);

        return tempFiles.withWorkDir(workDir -> {
            Path input = tempFiles.saveUpload(file, workDir, "input." + info.extension());
            Path output = workDir.resolve("output." + targetExtension);

            ffmpeg.run(workDir, input, output, MediaCommands.argsFor(target));

            byte[] data = Files.readAllBytes(output);
            return new FileResult(data, info.baseName() + "." + targetExtension, MediaCommands.mimeOf(target),
                    Files.size(input), data.length, null);
        });
    }

    // ---------- building the tables ----------

    private static Map<String, List<String>> audioConversions() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String format : AUDIO_FORMATS) {
            map.put(format, without(AUDIO_FORMATS, format));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<String>> videoConversions() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String format : VIDEO_FORMATS) {
            List<String> targets = new ArrayList<>(without(VIDEO_FORMATS, format));
            if (EXTRACT_FROM.contains(format)) {
                targets.addAll(EXTRACT_TO);
            }
            map.put(format, targets);
        }
        return Collections.unmodifiableMap(map);
    }

    private static List<String> without(List<String> formats, String excluded) {
        return formats.stream().filter(f -> !f.equals(excluded)).toList();
    }

    private static Map<String, String> extensionsOf(List<String> formats) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String format : formats) {
            map.put(format.toLowerCase(Locale.ROOT), format);
        }
        return map;
    }
}
