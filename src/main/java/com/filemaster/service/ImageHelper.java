package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import org.springframework.http.HttpStatus;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** Small static helpers for reading, resizing and writing images with the JDK's ImageIO. */
final class ImageHelper {

    /** Protects server memory from "decompression bombs" (tiny file, gigantic picture). */
    private static final long MAX_PIXELS = 20_000_000L;

    private ImageHelper() {
    }

    static BufferedImage read(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = in == null
                    ? java.util.Collections.emptyIterator()
                    : ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw FileMasterException.badRequest("This file is not a valid image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "Image dimensions are too large (limit is 20 megapixels).");
                }
                return applyOrientation(reader.read(0), exifOrientation(file));
            } catch (IIOException e) {
                throw FileMasterException.badRequest("This image is corrupted or uses an unsupported color format.");
            } finally {
                reader.dispose();
            }
        }
    }

    /** Reads only the image header, so a huge picture is rejected before FFmpeg opens it. */
    static void requireSafeSize(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = in == null
                    ? java.util.Collections.emptyIterator()
                    : ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw FileMasterException.badRequest("This file is not a valid image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "Image dimensions are too large (limit is 20 megapixels).");
                }
            } catch (IIOException e) {
                throw FileMasterException.badRequest("This image is corrupted or uses an unsupported color format.");
            } finally {
                reader.dispose();
            }
        }
    }

    // ---------- photo rotation ----------
    // Phones store photos in the sensor's direction and add a small "orientation" note (EXIF).
    // ImageIO ignores that note, and saving drops it, so photos would come out sideways.
    // These two methods read the note and turn the pixels the right way.

    /** EXIF orientation 1..8 of a JPEG file, or 1 (normal) if there is none. */
    static int exifOrientation(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] data = in.readNBytes(128 * 1024);        // EXIF is always near the start
            if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
                return 1;                                    // not a JPEG
            }
            int pos = 2;
            while (pos + 4 <= data.length) {
                if ((data[pos] & 0xFF) != 0xFF) {
                    return 1;
                }
                int marker = data[pos + 1] & 0xFF;
                if (marker == 0xFF) {
                    pos++;                                   // padding byte
                    continue;
                }
                if (marker == 0xDA || marker == 0xD9) {
                    return 1;                                // picture data starts: no EXIF
                }
                int length = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                boolean isExif = marker == 0xE1 && pos + 10 <= data.length
                        && data[pos + 4] == 'E' && data[pos + 5] == 'x' && data[pos + 6] == 'i' && data[pos + 7] == 'f';
                if (isExif) {
                    return tiffOrientation(data, pos + 10, Math.min(data.length, pos + 2 + length));
                }
                pos += 2 + length;
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable note simply means "leave the picture as it is"
        }
        return 1;
    }

    private static int tiffOrientation(byte[] d, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean little = d[tiff] == 'I' && d[tiff + 1] == 'I';
        boolean big = d[tiff] == 'M' && d[tiff + 1] == 'M';
        if (!little && !big) {
            return 1;
        }
        long ifdOffset = u32(d, tiff + 4, little);
        int ifd = (int) Math.min(Integer.MAX_VALUE - tiff, ifdOffset) + tiff;
        if (ifd < tiff || ifd + 2 > end) {
            return 1;
        }
        int entries = u16(d, ifd, little);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (u16(d, entry, little) == 0x0112) {
                int value = u16(d, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] d, int pos, boolean little) {
        int a = d[pos] & 0xFF;
        int b = d[pos + 1] & 0xFF;
        return little ? (b << 8) | a : (a << 8) | b;
    }

    private static long u32(byte[] d, int pos, boolean little) {
        long a = u16(d, pos, little);
        long b = u16(d, pos + 2, little);
        return little ? (b << 16) | a : (a << 16) | b;
    }

    /** Turns the pixels so the picture is upright. Orientation 1 returns the same image. */
    static BufferedImage applyOrientation(BufferedImage source, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return source;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = orientation >= 5;                     // 90 degree turns swap width and height
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, type);

        java.awt.geom.AffineTransform t = switch (orientation) {
            case 2 -> new java.awt.geom.AffineTransform(-1, 0, 0, 1, w, 0);   // mirrored
            case 3 -> new java.awt.geom.AffineTransform(-1, 0, 0, -1, w, h);  // upside down
            case 4 -> new java.awt.geom.AffineTransform(1, 0, 0, -1, 0, h);   // mirrored vertically
            case 5 -> new java.awt.geom.AffineTransform(0, 1, 1, 0, 0, 0);    // transposed
            case 6 -> new java.awt.geom.AffineTransform(0, 1, -1, 0, h, 0);   // turned 90 degrees clockwise
            case 7 -> new java.awt.geom.AffineTransform(0, -1, -1, 0, h, w);  // transversed
            default -> new java.awt.geom.AffineTransform(0, -1, 1, 0, 0, w);  // 8: turned 90 degrees counter-clockwise
        };
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, t, null);
        g.dispose();
        return target;
    }

    /** Returns a smaller copy. A factor of 1.0 or more returns the original image. */
    static BufferedImage scale(BufferedImage source, double factor) {
        if (factor >= 1.0) {
            return source;
        }
        int w = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(source.getHeight() * factor));
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(w, h, type);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return target;
    }

    static void writePng(BufferedImage image, Path out) throws IOException {
        if (!ImageIO.write(image, "png", out.toFile())) {
            throw new IOException("No PNG writer available");
        }
    }

    static void writeJpeg(BufferedImage image, Path out, float quality) throws IOException {
        BufferedImage rgb = toJpegCompatible(image);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out.toFile())) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /** JPEG has no transparency, so transparent areas are painted white. */
    private static BufferedImage toJpegCompatible(BufferedImage image) {
        int type = image.getType();
        boolean fine = !image.getColorModel().hasAlpha()
                && (type == BufferedImage.TYPE_INT_RGB
                || type == BufferedImage.TYPE_3BYTE_BGR
                || type == BufferedImage.TYPE_BYTE_GRAY);
        if (fine) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgb;
    }
}
