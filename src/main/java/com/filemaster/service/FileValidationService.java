package com.filemaster.service;

import com.filemaster.config.FileTypes;
import com.filemaster.exception.FileMasterException;
import com.filemaster.model.UploadInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks every upload before we touch it:
 *  1. a file was really sent and is not empty
 *  2. it is not bigger than the limit
 *  3. its name is cleaned (no folders, no odd characters)
 *  4. its extension is on the allow-list
 *  5. its first bytes ("magic bytes") match the extension, so a program
 *     renamed to photo.jpg is rejected
 */
@Service
public class FileValidationService {

    private final long maxBytes;
    private final long maxMb;
    private final int maxFiles;
    private final long maxTotalMb;
    private final long maxTotalBytes;

    public FileValidationService(@Value("${filemaster.max-file-size-mb:20}") long maxMb,
                                 @Value("${filemaster.max-files:8}") int maxFiles,
                                 @Value("${filemaster.max-total-size-mb:60}") long maxTotalMb) {
        this.maxMb = maxMb;
        this.maxBytes = maxMb * 1024 * 1024;
        this.maxFiles = maxFiles;
        this.maxTotalMb = maxTotalMb;
        this.maxTotalBytes = maxTotalMb * 1024 * 1024;
    }

    /** Validates several uploads at once (count, total size, then each file on its own). */
    public List<UploadInfo> validateAll(List<MultipartFile> files, Set<String> allowedExtensions) {
        if (files == null || files.isEmpty()) {
            throw FileMasterException.badRequest("Please choose at least one file.");
        }
        if (files.size() > maxFiles) {
            throw FileMasterException.badRequest("Too many files. You can upload up to " + maxFiles + " files at once.");
        }
        long total = 0;
        for (MultipartFile f : files) {
            total += f.getSize();
        }
        if (total > maxTotalBytes) {
            throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The files are too large together. The maximum total is " + maxTotalMb + " MB.");
        }
        return files.stream().map(f -> validate(f, allowedExtensions)).toList();
    }

    public UploadInfo validate(MultipartFile file, Set<String> allowedExtensions) {
        return validate(file, allowedExtensions, maxMb);
    }

    /** Same checks with a custom size limit (audio and video files may be bigger). */
    public UploadInfo validate(MultipartFile file, Set<String> allowedExtensions, long limitMb) {
        if (file == null || file.isEmpty()) {
            throw FileMasterException.badRequest("The file is empty. Please choose another file.");
        }
        if (file.getSize() > limitMb * 1024 * 1024) {
            throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File is too large. The maximum size is " + limitMb + " MB.");
        }

        String safeName = sanitizeFileName(file.getOriginalFilename());
        String extension = extensionOf(safeName);

        if (!allowedExtensions.contains(extension)) {
            String shown = extension.isEmpty() ? "without an extension" : "'." + extension + "'";
            throw new FileMasterException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported file type " + shown + ". Supported: "
                            + String.join(", ", FileTypes.sorted(allowedExtensions)) + ".");
        }

        verifyContent(file, extension);
        return new UploadInfo(baseNameOf(safeName), extension);
    }

    // ---------- helpers ----------

    /** Keeps only letters, digits, dot, dash and underscore; drops any folder part. */
    static String sanitizeFileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");

        String extension = extensionOf(name);
        String base = baseNameOf(name);
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        if (base.isBlank()) {
            base = "file";
        }
        return extension.isEmpty() ? base : base + "." + extension;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String baseNameOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private void verifyContent(MultipartFile file, String extension) {
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(8192);
        } catch (IOException e) {
            throw FileMasterException.badRequest("The file could not be read.");
        }

        boolean ok = switch (extension) {
            case "jpg", "jpeg" -> startsWith(head, 0xFF, 0xD8, 0xFF);
            case "png" -> startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "pdf" -> startsWith(head, '%', 'P', 'D', 'F');
            case "docx", "xlsx", "pptx" -> startsWith(head, 0x50, 0x4B, 0x03, 0x04);
            case "doc", "xls", "ppt" -> startsWith(head, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            case "mp3", "wav", "m4a", "aac", "ogg", "flac", "wma",
                 "mp4", "mov", "mkv", "avi", "webm", "wmv", "flv", "m4v", "webp" ->
                    MediaSignatures.matches(extension, head);
            default -> looksLikeText(head);
        };

        if (!ok) {
            throw new FileMasterException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "The file content does not match its '." + extension + "' extension.");
        }
    }

    private static boolean startsWith(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /** Text files never contain NUL bytes; binary files nearly always do. */
    private static boolean looksLikeText(byte[] data) {
        for (byte b : data) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }
}
