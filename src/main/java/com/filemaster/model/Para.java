package com.filemaster.model;

/**
 * One paragraph of document text. Every document format (TXT, DOC, DOCX, PDF)
 * is first read into a list of Para objects, then written out in the target format.
 * This keeps each conversion simple and lets us merge files easily.
 */
public record Para(String text, boolean bold, boolean pageBreakBefore) {

    public static Para plain(String text) {
        return new Para(text, false, false);
    }

    public static Para blank() {
        return new Para("", false, false);
    }

    public Para withPageBreak() {
        return new Para(text, bold, true);
    }
}
