package com.filemaster.model;

import com.filemaster.exception.FileMasterException;

import java.util.Locale;

/**
 * The three quality settings of the Image Compressor.
 * LOW = smallest file (some visible quality loss), HIGH = best quality (smaller savings).
 */
public enum ImageQuality {

    //     JPEG quality, PNG colours, WebP quality
    LOW(0.45f, 48, 40),
    MEDIUM(0.65f, 128, 65),
    HIGH(0.82f, 256, 82);

    private final float jpegQuality;
    private final int pngColors;
    private final int webpQuality;

    ImageQuality(float jpegQuality, int pngColors, int webpQuality) {
        this.jpegQuality = jpegQuality;
        this.pngColors = pngColors;
        this.webpQuality = webpQuality;
    }

    public float getJpegQuality() {
        return jpegQuality;
    }

    public int getPngColors() {
        return pngColors;
    }

    public int getWebpQuality() {
        return webpQuality;
    }

    public static ImageQuality from(String text) {
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw FileMasterException.badRequest("Unknown quality. Choose low, medium or high.");
        }
    }
}
