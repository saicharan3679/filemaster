package com.filemaster.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PDF layout (what "pdftohtml -xml" prints) goes in, a normal Word file must come out:
 * real paragraphs, bullets with hanging indents, pictures inline, and NO text boxes.
 */
class PdfFlowConverterTest {

    @TempDir
    Path dir;

    private static String line(int top, int left, int width, int height, int font, String text) {
        return "<text top=\"" + top + "\" left=\"" + left + "\" width=\"" + width + "\" height=\"" + height
                + "\" font=\"" + font + "\">" + text + "</text>";
    }

    /** One page, 1190 x 1684 units (A4 at 2 units per point). */
    private Path layout(String content) throws IOException {
        String xml = "<?xml version=\"1.0\"?><pdf2xml><page number=\"1\" position=\"absolute\" top=\"0\" left=\"0\" height=\"1684\" width=\"1190\">"
                + "<fontspec id=\"0\" size=\"28\" family=\"ABCDEF+Calibri-Bold\" color=\"#000000\"/>"
                + "<fontspec id=\"1\" size=\"24\" family=\"Calibri\" color=\"#000000\"/>"
                + content + "</page></pdf2xml>";
        return Files.writeString(dir.resolve("out.xml"), xml, StandardCharsets.UTF_8);
    }

    private String convert(String content, boolean withPicture) throws IOException {
        if (withPicture) {
            ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", dir.resolve("pic.png").toFile());
        }
        Path docx = dir.resolve("result.docx");
        PdfFlowConverter.convert(layout(content), dir, 2.0, docx);
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    private static final String SAMPLE =
            line(200, 126, 600, 34, 0, "<b>MAIN HEADING</b>")
            + line(260, 126, 1000, 30, 1, "This is the first line of a paragraph that is long enough ")
            + line(292, 126, 1000, 30, 1, "and continues on a second line that reaches the right edge.")
            + line(360, 126, 1000, 30, 1, "A second paragraph after a gap. It also fills the whole line width.")
            + line(430, 176, 10, 30, 1, "&#8226;") + line(430, 210, 900, 30, 1, "First bullet point")
            + line(468, 176, 10, 30, 1, "&#8226;") + line(468, 210, 900, 30, 1, "Second bullet point")
            + line(1600, 600, 10, 24, 1, "1");

    @Test
    void joinsLinesIntoParagraphsAndKeepsTheHeadingBold() throws IOException {
        String xml = convert(SAMPLE, false);

        assertTrue(xml.contains("This is the first line of a paragraph that is long enough and continues on a second line"),
                "the two lines of one paragraph are joined with a space");
        assertTrue(xml.contains("MAIN HEADING"));
        assertTrue(xml.indexOf("<w:b/>") > 0 && xml.indexOf("<w:b/>") < xml.indexOf("MAIN HEADING"), "heading run is bold");
        assertEquals(5, count(xml, "<w:p>"), "heading, 2 paragraphs and 2 bullets (page number removed)");
    }

    @Test
    void makesBulletsWithAHangingIndent() throws IOException {
        String xml = convert(SAMPLE, false);

        assertEquals(2, count(xml, "w:hanging="), "both bullets get a hanging indent");
        assertTrue(xml.contains("\u2022"), "the bullet character is kept");
        assertTrue(xml.contains("<w:tab/>"), "bullet and text are separated by a tab");
        assertTrue(xml.contains("First bullet point") && xml.contains("Second bullet point"));
    }

    @Test
    void removesPageNumbers() throws IOException {
        String xml = convert(SAMPLE, false);
        assertFalse(xml.contains(">1</w:t>"), "a lone page number at the bottom is not content");
    }

    @Test
    void placesPicturesInlineAndNeverUsesTextBoxes() throws IOException {
        String xml = convert(SAMPLE + "<image top=\"560\" left=\"126\" width=\"400\" height=\"200\" src=\"pic.png\"/>", true);

        assertEquals(1, count(xml, "<wp:inline"), "the picture sits in the text flow");
        assertEquals(0, count(xml, "<wp:anchor"), "no floating drawings");
        assertEquals(0, count(xml, "txbxContent"), "no text boxes");
    }

    @Test
    void ignoresTinyDecorationsAndKeepsNormalPictures() throws IOException {
        String xml = convert(SAMPLE
                + "<image top=\"560\" left=\"126\" width=\"6\" height=\"6\" src=\"pic.png\"/>"
                + "<image top=\"600\" left=\"126\" width=\"300\" height=\"150\" src=\"pic.png\"/>", true);
        assertEquals(1, count(xml, "<wp:inline"), "the 3 pt dot is a decoration, the big picture is content");
    }

    @Test
    void centresTitlesThatAreCentredInThePdf() throws IOException {
        // 400 wide, centred on x = 595 (the page centre)
        String xml = convert(line(200, 395, 400, 34, 0, "<b>CENTRED TITLE</b>") + SAMPLE.replace(line(200, 126, 600, 34, 0, "<b>MAIN HEADING</b>"), ""), false);
        assertTrue(xml.contains("<w:jc w:val=\"center\"/>"));
    }
}
