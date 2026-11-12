package com.filemaster;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Nothing may be lost": pictures, logos, non-English text and photo rotation must survive conversion.
 * Tests that need LibreOffice are SKIPPED (not failed) when it is not installed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentFidelityTests {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Pattern PDF_IMAGE = Pattern.compile("/Subtype\\s*/Image");

    @Autowired
    private MockMvc mockMvc;

    // ---------- helpers ----------

    private static boolean libreOfficeInstalled() {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String folder : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(folder, "soffice")) || Files.isExecutable(Path.of(folder, "soffice.exe"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean teluguFontInstalled() {
        try {
            Process p = new ProcessBuilder("fc-list", ":lang=te", "family").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return !out.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static BufferedImage logoImage() {
        BufferedImage img = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(28, 47, 70));
        g.fillRect(0, 0, 120, 60);
        g.setColor(new Color(154, 116, 51));
        g.fillOval(10, 10, 40, 40);
        g.dispose();
        return img;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] docxWithPicture(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun run = doc.createParagraph().createRun();
            run.setText(text);
            run.addPicture(new ByteArrayInputStream(png(logoImage())), XWPFDocument.PICTURE_TYPE_PNG, "logo.png",
                    Units.toEMU(120), Units.toEMU(60));
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdfWithPictureAndText(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDImageXObject image = LosslessFactory.createFromImage(doc, logoImage());
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.drawImage(image, 50, 650, 150, 75);
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                stream.newLineAtOffset(50, 600);
                stream.showText(text);
                stream.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A JPEG that carries an EXIF "orientation" note, like a photo taken with a phone. */
    private static byte[] jpegWithOrientation(int width, int height, int orientation) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", raw);
        byte[] jpeg = raw.toByteArray();

        ByteBuffer app1 = ByteBuffer.allocate(2 + 34);               // big-endian by default
        app1.put((byte) 0xFF).put((byte) 0xE1).putShort((short) 34);  // APP1 marker + length
        app1.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        app1.put("MM".getBytes(StandardCharsets.US_ASCII)).putShort((short) 42).putInt(8);   // TIFF header
        app1.putShort((short) 1);                                                            // one entry
        app1.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        app1.putInt(0);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                                       // start of image
        out.write(app1.array());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private MockHttpServletResponse convert(String name, byte[] data, String target) throws Exception {
        return mockMvc.perform(multipart("/api/convert")
                        .file(new MockMultipartFile("file", name, "application/octet-stream", data))
                        .param("targetFormat", target))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private static boolean pdfHasPicture(byte[] pdf) {
        return PDF_IMAGE.matcher(new String(pdf, StandardCharsets.ISO_8859_1)).find();
    }

    // ---------- documents ----------

    @Test
    void logoInsideAWordFileSurvivesConversionToPdf() throws Exception {
        assumeTrue(libreOfficeInstalled(), "LibreOffice is not installed");
        byte[] pdf = convert("logo.docx", docxWithPicture("Report with a logo"), "PDF").getContentAsByteArray();
        assertTrue(pdfHasPicture(pdf), "the picture must still be inside the PDF");
    }

    @Test
    void picturesInsideAPdfSurviveConversionToWord() throws Exception {
        assumeTrue(libreOfficeInstalled(), "LibreOffice is not installed");
        byte[] docx = convert("invoice.pdf", pdfWithPictureAndText("Invoice 1042"), "DOCX").getContentAsByteArray();

        boolean hasPicture = false;
        String documentXml = "";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith("word/media/")) {
                    hasPicture = true;
                }
                if (entry.getName().equals("word/document.xml")) {
                    documentXml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(hasPicture, "the picture must be stored in the Word file");
        assertTrue(documentXml.replaceAll("<[^>]+>", "").contains("Invoice 1042"), "the text must be kept too");
    }

    @Test
    void picturesSurviveWhenSeveralWordFilesAreMergedIntoOnePdf() throws Exception {
        assumeTrue(libreOfficeInstalled(), "LibreOffice is not installed");
        MockHttpServletResponse response = mockMvc.perform(multipart("/api/convert/merge")
                        .file(new MockMultipartFile("files", "a.docx", DOCX, docxWithPicture("First")))
                        .file(new MockMultipartFile("files", "b.docx", DOCX, docxWithPicture("Second")))
                        .param("targetFormat", "PDF"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        byte[] pdf = response.getContentAsByteArray();

        long pictures = PDF_IMAGE.matcher(new String(pdf, StandardCharsets.ISO_8859_1)).results().count();
        assertTrue(pictures >= 2, "both logos must be in the merged PDF, found " + pictures);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(2, doc.getNumberOfPages(), "one page per merged file");
        }
    }

    @Test
    void teluguAndHindiTextSurviveTextToPdf() throws Exception {
        assumeTrue(libreOfficeInstalled(), "LibreOffice is not installed");
        assumeTrue(teluguFontInstalled(), "no Telugu font is installed on this computer");
        byte[] pdf = convert("te.txt", "తెలుగు వచనం\nनमस्ते दुनिया".getBytes(StandardCharsets.UTF_8), "PDF")
                .getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("తెలుగు వచనం"), "Telugu text must not turn into '?'");
        }
    }

    @Test
    void wordToTextTellsHowManyPicturesCouldNotBeKept() throws Exception {
        MockHttpServletResponse response = convert("logo.docx", docxWithPicture("Text and a logo"), "TXT");
        String note = response.getHeader("X-Result-Note");
        assertTrue(note != null && note.contains("1 picture"), "the user must be warned, note was: " + note);
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("Text and a logo"));
    }

    // ---------- images ----------

    @Test
    void transparentPngToJpgSaysThatTransparencyIsLost() throws Exception {
        BufferedImage transparent = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);   // all transparent
        String note = convert("logo.png", png(transparent), "JPG").getHeader("X-Result-Note");
        assertTrue(note != null && note.toLowerCase().contains("transparen"), "note was: " + note);
    }

    @Test
    void aPhonePhotoIsNotTurnedSideways() throws Exception {
        // 200 wide x 100 high, marked "turn 90 degrees clockwise" (orientation 6): upright it is 100 x 200
        byte[] photo = jpegWithOrientation(200, 100, 6);
        byte[] png = convert("phone.jpg", photo, "PNG").getContentAsByteArray();

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(100, result.getWidth());
        assertEquals(200, result.getHeight());
    }
}
