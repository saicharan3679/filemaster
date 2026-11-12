package com.filemaster.service;

import com.filemaster.model.Para;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Draws paragraphs onto A4 pages with Apache PDFBox (word-wrap, bold, automatic page breaks). */
final class PdfHelper {

    private static final float MARGIN = 56f;
    private static final float FONT_SIZE = 11f;
    private static final float LINE_HEIGHT = 15f;

    private PdfHelper() {
    }

    /** Writes the PDF and returns how many characters had to be replaced by '?'. */
    static int write(List<Para> paragraphs, Path out) throws IOException {
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDRectangle pageSize = PDRectangle.A4;
        float maxWidth = pageSize.getWidth() - 2 * MARGIN;
        int[] replaced = {0};

        try (PDDocument doc = new PDDocument()) {
            PDPageContentStream stream = null;
            float y = 0;

            for (Para para : paragraphs) {
                PDType1Font font = para.bold() ? bold : regular;
                boolean firstLine = true;

                for (String line : wrap(para.text(), font, maxWidth, replaced)) {
                    boolean newPage = stream == null || y < MARGIN || (firstLine && para.pageBreakBefore());
                    if (newPage) {
                        if (stream != null) {
                            stream.close();
                        }
                        PDPage page = new PDPage(pageSize);
                        doc.addPage(page);
                        stream = new PDPageContentStream(doc, page);
                        y = pageSize.getHeight() - MARGIN;
                    }
                    if (!line.isEmpty()) {
                        stream.beginText();
                        stream.setFont(font, FONT_SIZE);
                        stream.newLineAtOffset(MARGIN, y);
                        stream.showText(line);
                        stream.endText();
                    }
                    y -= LINE_HEIGHT;
                    firstLine = false;
                }
            }

            if (stream != null) {
                stream.close();
            }
            if (doc.getNumberOfPages() == 0) {
                doc.addPage(new PDPage(pageSize));
            }
            doc.save(out.toFile());
        }
        return replaced[0];
    }

    private static List<String> wrap(String text, PDType1Font font, float maxWidth, int[] replaced)
            throws IOException {
        List<String> result = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");

        for (String paragraph : normalized.split("\n", -1)) {
            String clean = makeEncodable(paragraph, font, replaced);
            if (clean.isEmpty()) {
                result.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : clean.split(" ", -1)) {
                // A single word wider than the page is cut into pieces.
                while (width(font, word) > maxWidth) {
                    int cut = 1;
                    while (cut < word.length() && width(font, word.substring(0, cut + 1)) <= maxWidth) {
                        cut++;
                    }
                    if (line.length() > 0) {
                        result.add(line.toString());
                        line.setLength(0);
                    }
                    result.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (width(font, candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    result.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            result.add(line.toString());
        }
        return result;
    }

    private static float width(PDType1Font font, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * FONT_SIZE;
    }

    /** The built-in Helvetica font only knows Western characters; others become '?'. */
    private static String makeEncodable(String text, PDType1Font font, int[] replaced) throws IOException {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 32) {
                continue; // skip control characters
            }
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (IllegalArgumentException e) {
                sb.append('?');
                replaced[0]++;
            }
        }
        return sb.toString();
    }
}
