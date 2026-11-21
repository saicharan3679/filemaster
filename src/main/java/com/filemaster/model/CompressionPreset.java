package com.filemaster.model;

import com.filemaster.exception.FileMasterException;

import java.util.Locale;

/**
 * The three compression levels shown in the UI.
 * SMALL = smallest file (strongest compression, lowest quality)
 * HIGH = best quality (lightest compression, biggest file)
 */
public enum CompressionPreset {

    //     ZIP level, JPEG quality, JPEG scale, PNG scale
    SMALL(9, 0.40f, 0.70, 0.50),
    MEDIUM(6, 0.65f, 0.85, 0.75),
    HIGH(1, 0.85f, 1.00, 0.90);

    private final int deflateLevel;
    private final float jpegQuality;
    private final double jpegScale;
    private final double pngScale;

    CompressionPreset(int deflateLevel, float jpegQuality, double jpegScale, double pngScale) {
        this.deflateLevel = deflateLevel;
        this.jpegQuality = jpegQuality;
        this.jpegScale = jpegScale;
        this.pngScale = pngScale;
    }

    public int getDeflateLevel() {
        return deflateLevel;
    }

    public float getJpegQuality() {
        return jpegQuality;
    }

    public double getJpegScale() {
        return jpegScale;
    }

    public double getPngScale() {
        return pngScale;
    }

    public static CompressionPreset from(String text) {
        try {
            String key = text.trim().toUpperCase(Locale.ROOT);
            return valueOf(key.equals("LARGE") ? "HIGH" : key); // "large" = old name for "high"
        } catch (RuntimeException e) {
            throw FileMasterException.badRequest("Unknown compression level. Choose small, medium or high.");
        }
    }
}
