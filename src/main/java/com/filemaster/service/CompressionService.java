package com.filemaster.service;

import com.filemaster.config.FileTypes;
import com.filemaster.model.CompressionPreset;
import com.filemaster.model.EstimateResponse;
import com.filemaster.model.FileResult;
import com.filemaster.model.PresetEstimate;
import com.filemaster.model.UploadInfo;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Real compression:
 *  - JPG / PNG  -> the picture is shrunk (and JPEG quality lowered) so the file really gets smaller.
 *  - everything else -> packed into a ZIP archive using the Deflate algorithm.
 * If an image cannot be made smaller we return the original and say so.
 *
 * Three ways to use it:
 *  - compress()       one file
 *  - compressMerged() several files packed into ONE zip archive
 *  - estimate()       the real result size for Small, Medium and High, so the user can choose
 */
@Service
public class CompressionService {

    /** An upload that has been validated and saved in the temp folder. */
    private record Saved(Path path, UploadInfo info, long size) {
    }

    private record ImageShrink(byte[] data, String extension, String mime, boolean smaller) {
    }

    private final FileValidationService validator;
    private final TempFileService tempFiles;

    public CompressionService(FileValidationService validator, TempFileService tempFiles) {
        this.validator = validator;
        this.tempFiles = tempFiles;
    }

    // ---------- public API ----------

    public FileResult compress(MultipartFile file, String presetName) {
        CompressionPreset preset = CompressionPreset.from(presetName);
        UploadInfo info = validator.validate(file, FileTypes.COMPRESSIBLE);

        return tempFiles.withWorkDir(workDir -> {
            Saved saved = save(file, info, workDir, 0);
            return compressOne(saved, preset, workDir);
        });
    }

    public FileResult compressMerged(List<MultipartFile> files, String presetName) {
        CompressionPreset preset = CompressionPreset.from(presetName);
        List<UploadInfo> infos = validator.validateAll(files, FileTypes.COMPRESSIBLE);

        return tempFiles.withWorkDir(workDir -> {
            List<Saved> saved = saveAll(files, infos, workDir);
            long originalSize = totalSize(saved);
            byte[] data = buildArchive(saved, preset, workDir);
            String note = data.length >= originalSize
                    ? "These files are already compressed, so the ZIP cannot make them much smaller."
                    : null;
            return new FileResult(data, "filemaster-compressed.zip", "application/zip",
                    originalSize, data.length, note);
        });
    }

    /** Runs every preset for real and reports the exact size each one would produce. */
    public EstimateResponse estimate(List<MultipartFile> files, String mode) {
        boolean merge = "merge".equalsIgnoreCase(mode);
        List<UploadInfo> infos = validator.validateAll(files, FileTypes.COMPRESSIBLE);

        return tempFiles.withWorkDir(workDir -> {
            List<Saved> saved = saveAll(files, infos, workDir);
            long originalSize = totalSize(saved);
            List<PresetEstimate> estimates = new ArrayList<>();

            for (CompressionPreset preset : CompressionPreset.values()) {
                long size = 0;
                if (merge && saved.size() > 1) {
                    size = buildArchive(saved, preset, workDir).length;
                } else {
                    for (Saved s : saved) {
                        size += compressOne(s, preset, workDir).resultSize();
                    }
                }
                estimates.add(new PresetEstimate(preset.name().toLowerCase(Locale.ROOT), size,
                        FileResult.percent(originalSize, size)));
            }
            return new EstimateResponse(originalSize, estimates);
        });
    }

    // ---------- one file ----------

    private FileResult compressOne(Saved saved, CompressionPreset preset, Path workDir) throws IOException {
        if (FileTypes.IMAGE.contains(saved.info().extension())) {
            return compressImage(saved, preset, workDir);
        }
        return compressToZip(saved, preset, workDir);
    }

    private FileResult compressToZip(Saved saved, CompressionPreset preset, Path workDir) throws IOException {
        UploadInfo info = saved.info();
        Path zip = Files.createTempFile(workDir, "zip-", ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.setLevel(preset.getDeflateLevel());
            zos.putNextEntry(new ZipEntry(info.baseName() + "." + info.extension()));
            Files.copy(saved.path(), zos);
            zos.closeEntry();
        }

        byte[] data = Files.readAllBytes(zip);
        String note = data.length >= saved.size()
                ? "This file type is already compressed, so ZIP cannot make it much smaller."
                : null;
        return new FileResult(data, info.baseName() + ".zip", "application/zip",
                saved.size(), data.length, note);
    }

    private FileResult compressImage(Saved saved, CompressionPreset preset, Path workDir) throws IOException {
        UploadInfo info = saved.info();
        ImageShrink shrink = shrinkImage(saved, preset, workDir);

        if (!shrink.smaller()) {
            // Never hand back something bigger than what the user gave us.
            return new FileResult(shrink.data(), info.baseName() + "-original." + shrink.extension(), shrink.mime(),
                    saved.size(), saved.size(),
                    "This image is already well optimized, so the original is returned. Try the Small level.");
        }
        return new FileResult(shrink.data(), info.baseName() + "-compressed." + shrink.extension(), shrink.mime(),
                saved.size(), shrink.data().length, null);
    }

    private ImageShrink shrinkImage(Saved saved, CompressionPreset preset, Path workDir) throws IOException {
        boolean isPng = saved.info().extension().equals("png");
        BufferedImage image = ImageHelper.read(saved.path());
        Path output = Files.createTempFile(workDir, "img-", isPng ? ".png" : ".jpg");

        if (isPng) {
            ImageHelper.writePng(ImageHelper.scale(image, preset.getPngScale()), output);
        } else {
            ImageHelper.writeJpeg(ImageHelper.scale(image, preset.getJpegScale()), output, preset.getJpegQuality());
        }

        if (Files.size(output) >= saved.size()) {
            String mime = isPng ? "image/png" : "image/jpeg";
            return new ImageShrink(Files.readAllBytes(saved.path()), saved.info().extension(), mime, false);
        }
        return new ImageShrink(Files.readAllBytes(output), isPng ? "png" : "jpg",
                isPng ? "image/png" : "image/jpeg", true);
    }

    // ---------- several files in one ZIP ----------

    private byte[] buildArchive(List<Saved> files, CompressionPreset preset, Path workDir) throws IOException {
        Path zip = Files.createTempFile(workDir, "archive-", ".zip");
        Set<String> usedNames = new HashSet<>();

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Saved saved : files) {
                UploadInfo info = saved.info();
                if (FileTypes.IMAGE.contains(info.extension())) {
                    ImageShrink shrink = shrinkImage(saved, preset, workDir);
                    zos.setLevel(0); // pictures are already compressed; storing them is faster
                    zos.putNextEntry(new ZipEntry(uniqueName(usedNames, info.baseName(), shrink.extension())));
                    zos.write(shrink.data());
                } else {
                    zos.setLevel(preset.getDeflateLevel());
                    zos.putNextEntry(new ZipEntry(uniqueName(usedNames, info.baseName(), info.extension())));
                    Files.copy(saved.path(), zos);
                }
                zos.closeEntry();
            }
        }
        return Files.readAllBytes(zip);
    }

    /** Two files may share a name (photo.jpg twice): the second becomes photo-2.jpg. */
    private static String uniqueName(Set<String> used, String base, String extension) {
        String name = base + "." + extension;
        int counter = 2;
        while (!used.add(name)) {
            name = base + "-" + counter + "." + extension;
            counter++;
        }
        return name;
    }

    // ---------- helpers ----------

    private Saved save(MultipartFile file, UploadInfo info, Path workDir, int index) throws IOException {
        Path path = tempFiles.saveUpload(file, workDir, "input-" + index + "." + info.extension());
        return new Saved(path, info, Files.size(path));
    }

    private List<Saved> saveAll(List<MultipartFile> files, List<UploadInfo> infos, Path workDir) throws IOException {
        List<Saved> saved = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            saved.add(save(files.get(i), infos.get(i), workDir, i));
        }
        return saved;
    }

    private static long totalSize(List<Saved> files) {
        long total = 0;
        for (Saved s : files) {
            total += s.size();
        }
        return total;
    }
}
