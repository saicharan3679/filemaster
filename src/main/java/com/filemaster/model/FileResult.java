package com.filemaster.model;

/** What a service hands back to a controller: the output file plus size information. */
public record FileResult(byte[] data,
                         String fileName,
                         String mimeType,
                         long originalSize,
                         long resultSize,
                         String note) {

    /** Percentage saved, rounded to 1 decimal. Never negative. */
    public double percentSaved() {
        return percent(originalSize, resultSize);
    }

    public static double percent(long originalSize, long resultSize) {
        if (originalSize <= 0) {
            return 0;
        }
        double saved = (1.0 - (double) resultSize / originalSize) * 100.0;
        return Math.max(0, Math.round(saved * 10.0) / 10.0);
    }
}
