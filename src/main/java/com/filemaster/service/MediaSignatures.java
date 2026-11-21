package com.filemaster.service;

/**
 * "Magic bytes" of audio, video and WebP files.
 * A real MP3 starts with different bytes than a real MP4, so a text file renamed to
 * "song.mp3" is rejected before FFmpeg ever sees it.
 */
final class MediaSignatures {

    private MediaSignatures() {
    }

    static boolean matches(String extension, byte[] h) {
        return switch (extension) {
            case "mp3" -> id3(h) || mpegAudioFrame(h);
            case "aac" -> id3(h) || adtsFrame(h) || ftyp(h);
            case "flac" -> id3(h) || ascii(h, 0, "fLaC");
            case "ogg" -> ascii(h, 0, "OggS");
            case "wav" -> riff(h, "WAVE");
            case "avi" -> riff(h, "AVI ");
            case "webp" -> riff(h, "WEBP");
            case "m4a", "mp4", "m4v" -> ftyp(h);
            case "mov" -> ftyp(h) || ascii(h, 4, "moov") || ascii(h, 4, "mdat")
                    || ascii(h, 4, "wide") || ascii(h, 4, "free") || ascii(h, 4, "skip");
            case "wma", "wmv" -> startsWith(h, 0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
                    0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C);      // ASF header
            case "mkv", "webm" -> startsWith(h, 0x1A, 0x45, 0xDF, 0xA3);   // EBML header
            case "flv" -> ascii(h, 0, "FLV") && h.length > 3 && h[3] == 1;
            default -> false;
        };
    }

    // ---------- WebP details (FFmpeg 6 cannot read animated WebP) ----------

    /** True when the WebP file is an animation. */
    static boolean isAnimatedWebp(byte[] h) {
        return ascii(h, 12, "VP8X") && h.length > 20 && (h[20] & 0x02) != 0;
    }

    /** Width x height of a WebP file read from its header, or -1 if the header is not understood. */
    static long webpPixels(byte[] h) {
        if (!riff(h, "WEBP") || h.length < 30) {
            return -1;
        }
        if (ascii(h, 12, "VP8X")) {                       // extended format
            long width = 1L + u24(h, 24);
            long height = 1L + u24(h, 27);
            return width * height;
        }
        if (ascii(h, 12, "VP8 ")) {                       // lossy
            if (u8(h, 23) != 0x9D || u8(h, 24) != 0x01 || u8(h, 25) != 0x2A) {
                return -1;
            }
            long width = (u8(h, 26) | u8(h, 27) << 8) & 0x3FFF;
            long height = (u8(h, 28) | u8(h, 29) << 8) & 0x3FFF;
            return width * height;
        }
        if (ascii(h, 12, "VP8L")) {                       // lossless
            if (u8(h, 20) != 0x2F) {
                return -1;
            }
            long bits = u8(h, 21) | u8(h, 22) << 8 | u8(h, 23) << 16 | (long) u8(h, 24) << 24;
            long width = (bits & 0x3FFF) + 1;
            long height = ((bits >> 14) & 0x3FFF) + 1;
            return width * height;
        }
        return -1;
    }

    // ---------- helpers ----------

    private static boolean id3(byte[] h) {
        return ascii(h, 0, "ID3");
    }

    /** MP3 frame header: 11 sync bits, "layer III", and a valid MPEG version. */
    private static boolean mpegAudioFrame(byte[] h) {
        return h.length >= 2 && u8(h, 0) == 0xFF && (u8(h, 1) & 0xE0) == 0xE0
                && (u8(h, 1) & 0x06) == 0x02 && (u8(h, 1) & 0x18) != 0x08;
    }

    /** Raw AAC (ADTS) frame header. */
    private static boolean adtsFrame(byte[] h) {
        return h.length >= 2 && u8(h, 0) == 0xFF && (u8(h, 1) & 0xF6) == 0xF0;
    }

    /** MP4-family files start with a size, then the word "ftyp". */
    private static boolean ftyp(byte[] h) {
        return ascii(h, 4, "ftyp");
    }

    private static boolean riff(byte[] h, String form) {
        return ascii(h, 0, "RIFF") && ascii(h, 8, form);
    }

    private static boolean ascii(byte[] h, int offset, String text) {
        if (h.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (h[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] h, int... signature) {
        if (h.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (u8(h, i) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u8(byte[] h, int index) {
        return h[index] & 0xFF;
    }

    private static int u24(byte[] h, int index) {
        return u8(h, index) | u8(h, index + 1) << 8 | u8(h, index + 2) << 16;
    }
}
