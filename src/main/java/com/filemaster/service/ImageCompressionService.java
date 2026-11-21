package com.filemaster.service;

import com.filemaster.config.FileTypes;
import com.filemaster.exception.FileMasterException;
import com.filemaster.model.FileResult;
import com.filemaster.model.ImageQuality;
import com.filemaster.model.UploadInfo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Image Compressor. The picture keeps its width and height; the file gets smaller because:
 *  - JPG  -> re-encoded with a lower JPEG quality (Java ImageIO)
 *  - PNG  -> colours reduced to a smaller palette, like the "pngquant" tool (FFmpeg)
 *  - WebP -> re-encoded with a lower WebP quality (FFmpeg)
 * If the result is not smaller than the original, the original is returned and the user is told.
 */
@Service
public class ImageCompressionService {

    private static final long MAX_PIXELS = 20_000_000L;

    private final FileValidationService validator;
    private final TempFileService tempFiles;
    private final FfmpegService ffmpeg;

    public ImageCompressionService(FileValidationService validator, TempFileService tempFiles, FfmpegService ffmpeg) {
        this.validator = validator;
        this.tempFiles = tempFiles;
        this.ffmpeg = ffmpeg;
    }

    public FileResult compress(MultipartFile file, String qualityName) {
        ImageQuality quality = ImageQuality.from(qualityName);
        UploadInfo info = validator.validate(file, FileTypes.IMAGE_FORMATS);
        String extension = info.extension();

        return tempFiles.withWorkDir(workDir -> {
            Path input = tempFiles.saveUpload(file, workDir, "input." + extension);
            long originalSize = Files.size(input);

            Path output = switch (extension) {
                case "png" -> compressPng(input, workDir, quality);
                case "webp" -> compressWebp(input, workDir, quality);
                default -> compressJpeg(input, workDir, quality);
            };

            String outExtension = extension.equals("jpeg") ? "jpg" : extension;
            String mime = switch (outExtension) {
                case "png" -> "image/png";
                case "webp" -> "image/webp";
                default -> "image/jpeg";
            };

            long newSize = Files.size(output);
            if (newSize >= originalSize) {
                // Never hand back something bigger than what the user gave us.
                return new FileResult(Files.readAllBytes(input), info.baseName() + "-original." + extension, mime,
                        originalSize, originalSize,
                        "This image is already well optimized, so the original is returned. Try the Low quality setting.");
            }
            byte[] data = Files.readAllBytes(output);
            return new FileResult(data, info.baseName() + "-compressed." + outExtension, mime,
                    originalSize, data.length, null);
        });
    }

    private Path compressJpeg(Path input, Path workDir, ImageQuality quality) throws IOException {
        Path output = Files.createTempFile(workDir, "img-", ".jpg");
        ImageHelper.writeJpeg(ImageHelper.read(input), output, quality.getJpegQuality());
        return output;
    }

    private Path compressPng(Path input, Path workDir, ImageQuality quality) throws IOException {
        ImageHelper.requireSafeSize(input);
        Path output = workDir.resolve("output.png");
        ffmpeg.run(workDir, input, output, MediaCommands.pngArgs(quality.getPngColors()));
        return output;
    }

    private Path compressWebp(Path input, Path workDir, ImageQuality quality) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(input)) {
            head = in.readNBytes(64);
        }
        if (MediaSignatures.isAnimatedWebp(head)) {
            throw FileMasterException.badRequest("Animated WebP files are not supported. Please use a still image.");
        }
        long pixels = MediaSignatures.webpPixels(head);
        if (pixels < 0) {
            throw FileMasterException.badRequest("This WebP file could not be read.");
        }
        if (pixels > MAX_PIXELS) {
            throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image dimensions are too large (limit is 20 megapixels).");
        }
        Path output = workDir.resolve("output.webp");
        ffmpeg.run(workDir, input, output, MediaCommands.webpArgs(quality.getWebpQuality()));
        return output;
    }
}
