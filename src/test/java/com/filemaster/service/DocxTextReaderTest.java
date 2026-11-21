package com.filemaster.service;

import com.filemaster.model.Para;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reader must not miss text that sits in tables, text boxes, headers or footers. */
class DocxTextReaderTest {

    @TempDir
    Path dir;

    private static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
            + "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"";

    private Path docx(String body, String header, String footer, String... mediaNames) throws IOException {
        Path file = dir.resolve("test.docx");
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            put(zip, "[Content_Types].xml", "<Types/>");
            put(zip, "word/document.xml", "<w:document " + W + "><w:body>" + body + "</w:body></w:document>");
            if (header != null) {
                put(zip, "word/header1.xml", "<w:hdr " + W + ">" + header + "</w:hdr>");
            }
            if (footer != null) {
                put(zip, "word/footer1.xml", "<w:ftr " + W + ">" + footer + "</w:ftr>");
            }
            for (String media : mediaNames) {
                put(zip, "word/media/" + media, "picture-bytes");
            }
        }
        return file;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String p(String text) {
        return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    private static String all(List<Para> paragraphs) {
        return paragraphs.stream().map(Para::text).collect(Collectors.joining("\n"));
    }

    @Test
    void readsHeaderBodyTableAndFooter() throws IOException {
        String table = "<w:tbl><w:tr><w:tc>" + p("Item") + "</w:tc><w:tc>" + p("Qty") + "</w:tc></w:tr></w:tbl>";
        Path file = docx(p("Hello") + table, p("ACME header"), p("Footer text"), "image1.png", "image2.jpg");

        String text = all(DocxTextReader.read(file));

        assertTrue(text.contains("ACME header"));
        assertTrue(text.contains("Hello"));
        assertTrue(text.contains("Item   |   Qty"));
        assertTrue(text.contains("Footer text"));
        assertEquals(2, DocxTextReader.pictureCount(file));
    }

    @Test
    void readsTextInsideTextBoxesOnlyOnce() throws IOException {
        // Word stores a text box twice: a modern copy (Choice) and an old-Word copy (Fallback)
        String box = "<w:p><w:r><mc:AlternateContent>"
                + "<mc:Choice><w:txbxContent>" + p("Invoice 1042") + "</w:txbxContent></mc:Choice>"
                + "<mc:Fallback><w:txbxContent>" + p("Invoice 1042") + "</w:txbxContent></mc:Fallback>"
                + "</mc:AlternateContent></w:r></w:p>";
        Path file = docx(box, null, null);

        String text = all(DocxTextReader.read(file));

        assertEquals(1, text.split("Invoice 1042", -1).length - 1, "the text box text appears exactly once");
    }

    @Test
    void keepsNonEnglishText() throws IOException {
        Path file = docx(p("తెలుగు వచనం") + p("नमस्ते दुनिया"), null, null);
        String text = all(DocxTextReader.read(file));
        assertTrue(text.contains("తెలుగు వచనం") && text.contains("नमस्ते दुनिया"));
    }

    // ---------- pictures ----------

    private Path docxWithPictures(String body, String relationships, String... mediaNames) throws IOException {
        Path file = dir.resolve("pictures.docx");
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            put(zip, "[Content_Types].xml", "<Types/>");
            put(zip, "word/document.xml", "<w:document " + W + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
                    + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>" + body + "</w:body></w:document>");
            put(zip, "word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + relationships + "</Relationships>");
            for (String media : mediaNames) {
                put(zip, "word/media/" + media, "picture-bytes-of-" + media);
            }
        }
        return file;
    }

    private static String drawing(String relationshipId, long cx, long cy) {
        return "<w:r><w:drawing><wp:inline><wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<a:graphic><a:graphicData><a:blip r:embed=\"" + relationshipId + "\"/></a:graphicData></a:graphic>"
                + "</wp:inline></w:drawing></w:r>";
    }

    @Test
    void readsPicturesInDocumentOrderWithTheirSize() throws IOException {
        String body = "<w:p><w:r><w:t>Before the logo</w:t></w:r>" + drawing("rId5", 1270000, 635000) + "</w:p>" + p("After the logo");
        Path file = docxWithPictures(body,
                "<Relationship Id=\"rId5\" Type=\"http://x/image\" Target=\"media/image1.png\"/>", "image1.png");

        List<Para> paragraphs = DocxTextReader.read(file);

        assertEquals(3, paragraphs.size(), "text, picture, text");
        assertEquals("Before the logo", paragraphs.get(0).text());
        assertTrue(paragraphs.get(1).picture() != null, "the picture comes right after the text of its paragraph");
        assertEquals("png", paragraphs.get(1).picture().extension());
        assertEquals(100, (int) paragraphs.get(1).picture().widthPt(), "1270000 EMU = 100 points");
        assertEquals(50, (int) paragraphs.get(1).picture().heightPt());
        assertEquals("After the logo", paragraphs.get(2).text());
    }

    @Test
    void ignoresPicturesItCannotUseOrThatPointOutsideTheFile() throws IOException {
        String body = "<w:p>" + drawing("rId1", 100, 100) + drawing("rId2", 100, 100) + "</w:p>" + p("Text");
        Path file = docxWithPictures(body,
                "<Relationship Id=\"rId1\" Type=\"http://x/image\" Target=\"media/drawing.emf\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"http://x/image\" Target=\"http://evil.test/x.png\" TargetMode=\"External\"/>",
                "drawing.emf");

        List<Para> paragraphs = DocxTextReader.read(file);

        assertTrue(paragraphs.stream().noneMatch(para -> para.picture() != null),
                "an .emf picture is not supported and a web address is never fetched");
        assertTrue(all(paragraphs).contains("Text"));
    }
}
