package com.filemaster.model;

/**
 * One paragraph of a document: either text, or a picture (when picture is not null).
 * Every document format (TXT, DOC, DOCX, PDF) is first read into a list of Para objects, then written out in the
 * target format. This keeps each conversion simple and lets us merge files easily.
 */
public record Para(String text, boolean bold, boolean pageBreakBefore, Picture picture) {

    /** A picture taken from a document. Sizes are in points; 0 means "use the picture's own size". */
    public record Picture(byte[] data, String extension, double widthPt, double heightPt) {
    }

    /** A paragraph of text (no picture). */
    public Para(String text, boolean bold, boolean pageBreakBefore) {
        this(text, bold, pageBreakBefore, null);
    }

    public static Para plain(String text) {
        return new Para(text, false, false);
    }

    public static Para blank() {
        return new Para("", false, false);
    }

    public static Para ofPicture(Picture picture) {
        return new Para("", false, false, picture);
    }

    public Para withPageBreak() {
        return new Para(text, bold, true, picture);
    }

    /** True for an empty line: no visible text and no picture. */
    public boolean isEmpty() {
        return text.isBlank() && picture == null;
    }
}
