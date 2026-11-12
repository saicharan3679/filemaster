package com.filemaster;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests: each test sends a real HTTP-style request through the
 * controllers and services and checks the bytes that come back.
 * Run with:  mvn test
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileMasterApplicationTests {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired
    private MockMvc mockMvc;

    // ---------- test data and helpers ----------

    private static byte[] noisyImage(String format) throws IOException {
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static MockMultipartFile upload(String name, String type, byte[] bytes) {
        return new MockMultipartFile("file", name, type, bytes);
    }

    private static MockMultipartFile uploadMany(String name, String type, byte[] bytes) {
        return new MockMultipartFile("files", name, type, bytes);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Converts one file and returns the bytes of the result. */
    private byte[] convert(String name, String type, byte[] data, String target) throws Exception {
        return mockMvc.perform(multipart("/api/convert")
                        .file(upload(name, type, data))
                        .param("targetFormat", target))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    // ---------- basic ----------

    @Test
    void homepageIsServed() throws Exception {
        mockMvc.perform(get("/index.html")).andExpect(status().isOk());
    }

    @Test
    void healthEndpointWorks() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void formatsEndpointListsConversions() throws Exception {
        mockMvc.perform(get("/api/convert/formats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensions.docx").value("DOCX"))
                .andExpect(jsonPath("$.conversions.TXT[1]").value("DOCX"))
                .andExpect(jsonPath("$.conversions.PDF[0]").value("DOCX"));
    }

    // ---------- image conversions ----------

    @Test
    void convertsPngToRealJpeg() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(multipart("/api/convert")
                        .file(upload("pic.png", "image/png", noisyImage("png")))
                        .param("targetFormat", "JPG"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("pic.jpg")))
                .andReturn().getResponse();

        byte[] body = response.getContentAsByteArray();
        assertEquals("image/jpeg", response.getContentType());
        assertTrue((body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8, "output must start with JPEG signature");
        assertNotNull(ImageIO.read(new ByteArrayInputStream(body)), "output must be a readable image");
    }

    @Test
    void convertsJpgToRealPng() throws Exception {
        byte[] body = convert("photo.jpg", "image/jpeg", noisyImage("jpg"), "PNG");
        assertEquals(0x89, body[0] & 0xFF);
        assertEquals('P', body[1]);
        assertEquals('N', body[2]);
        assertEquals('G', body[3]);
    }

    @Test
    void rejectsUnsupportedConversion() throws Exception {
        mockMvc.perform(multipart("/api/convert")
                        .file(upload("pic.png", "image/png", noisyImage("png")))
                        .param("targetFormat", "PDF"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ---------- document conversions ----------

    @Test
    void convertsTextToRealPdf() throws Exception {
        byte[] body = convert("notes.txt", "text/plain", utf8("Hello FileMaster!\nSecond line.".repeat(200)), "PDF");
        assertEquals("%PDF", new String(body, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void textToDocxAndBackToTextKeepsTheWords() throws Exception {
        byte[] docx = convert("note.txt", "text/plain", utf8("Hello DOCX world\nSecond paragraph"), "DOCX");
        assertEquals('P', docx[0]);   // a .docx file is a ZIP archive: starts with "PK"
        assertEquals('K', docx[1]);

        byte[] txt = convert("note.docx", DOCX, docx, "TXT");
        String text = new String(txt, StandardCharsets.UTF_8);
        assertTrue(text.contains("Hello DOCX world"));
        assertTrue(text.contains("Second paragraph"));
    }

    @Test
    void convertsDocxToPdf() throws Exception {
        byte[] docx = convert("note.txt", "text/plain", utf8("Word to PDF test"), "DOCX");
        byte[] pdf = convert("note.docx", DOCX, docx, "PDF");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void convertsPdfToDocx() throws Exception {
        byte[] pdf = convert("note.txt", "text/plain", utf8("PDF to Word test"), "PDF");
        byte[] docx = convert("note.pdf", "application/pdf", pdf, "DOCX");
        assertEquals('P', docx[0]);

        byte[] txt = convert("note.docx", DOCX, docx, "TXT");
        assertTrue(new String(txt, StandardCharsets.UTF_8).contains("PDF to Word test"));
    }

    @Test
    void mergesTextFilesIntoOnePdfInOrder() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(multipart("/api/convert/merge")
                        .file(uploadMany("a.txt", "text/plain", utf8("ALPHA first file")))
                        .file(uploadMany("b.txt", "text/plain", utf8("BETA second file")))
                        .param("targetFormat", "PDF"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("filemaster-merged.pdf")))
                .andReturn().getResponse();
        byte[] pdf = response.getContentAsByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));

        // read the merged PDF back as text to prove both files are inside, in the right order
        byte[] docx = convert("merged.pdf", "application/pdf", pdf, "DOCX");
        String text = new String(convert("merged.docx", DOCX, docx, "TXT"), StandardCharsets.UTF_8);
        assertTrue(text.indexOf("ALPHA") >= 0 && text.indexOf("BETA") > text.indexOf("ALPHA"));
    }

    @Test
    void mergeRejectsImages() throws Exception {
        mockMvc.perform(multipart("/api/convert/merge")
                        .file(uploadMany("a.png", "image/png", noisyImage("png")))
                        .file(uploadMany("b.png", "image/png", noisyImage("png")))
                        .param("targetFormat", "JPG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ---------- compressor ----------

    @Test
    void zipsTextFileAndReallyShrinksIt() throws Exception {
        byte[] text = utf8("hello world ".repeat(2000));
        MockHttpServletResponse response = mockMvc.perform(multipart("/api/compress")
                        .file(upload("notes.txt", "text/plain", text))
                        .param("preset", "small"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("notes.zip")))
                .andReturn().getResponse();

        byte[] zip = response.getContentAsByteArray();
        assertTrue(zip.length < text.length, "zip must be smaller than the original");
        assertTrue(Double.parseDouble(response.getHeader("X-Saved-Percent")) > 50);

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = in.getNextEntry();
            assertNotNull(entry);
            assertEquals("notes.txt", entry.getName());
            assertEquals(text.length, in.readAllBytes().length);
        }
    }

    @Test
    void compressesJpegImage() throws Exception {
        byte[] original = noisyImage("jpg");
        MockHttpServletResponse response = mockMvc.perform(multipart("/api/compress")
                        .file(upload("photo.jpg", "image/jpeg", original))
                        .param("preset", "small"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("photo-compressed.jpg")))
                .andReturn().getResponse();

        assertTrue(response.getContentAsByteArray().length < original.length, "image must get smaller");
        assertNotNull(ImageIO.read(new ByteArrayInputStream(response.getContentAsByteArray())));
    }

    @Test
    void mergesSeveralFilesIntoOneZip() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(multipart("/api/compress/merge")
                        .file(uploadMany("notes.txt", "text/plain", utf8("hello world ".repeat(500))))
                        .file(uploadMany("photo.jpg", "image/jpeg", noisyImage("jpg")))
                        .param("preset", "medium"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("filemaster-compressed.zip")))
                .andReturn().getResponse();

        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        assertEquals(List.of("notes.txt", "photo.jpg"), names);
    }

    @Test
    void estimateReportsRealSizesForEveryLevel() throws Exception {
        mockMvc.perform(multipart("/api/compress/estimate")
                        .file(uploadMany("photo.jpg", "image/jpeg", noisyImage("jpg")))
                        .param("mode", "individual"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presets.length()").value(3))
                .andExpect(jsonPath("$.presets[0].preset").value("small"))
                .andExpect(jsonPath("$.presets[1].preset").value("medium"))
                .andExpect(jsonPath("$.presets[2].preset").value("high"))
                .andExpect(jsonPath("$.presets[0].size").isNumber())
                .andExpect(jsonPath("$.originalSize").isNumber());
    }

    // ---------- validation / security ----------

    @Test
    void rejectsFileWhoseContentDoesNotMatchExtension() throws Exception {
        mockMvc.perform(multipart("/api/compress")
                        .file(upload("fake.jpg", "image/jpeg", utf8("I am not an image"))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsDangerousExtension() throws Exception {
        mockMvc.perform(multipart("/api/compress")
                        .file(upload("virus.exe", "application/octet-stream", utf8("MZ"))))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        mockMvc.perform(multipart("/api/compress").file(upload("empty.txt", "text/plain", new byte[0])))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsRequestWithoutFile() throws Exception {
        mockMvc.perform(multipart("/api/compress"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsTooManyFiles() throws Exception {
        var request = multipart("/api/compress/estimate");
        for (int i = 0; i < 9; i++) {
            request.file(uploadMany("f" + i + ".txt", "text/plain", utf8("hello")));
        }
        mockMvc.perform(request).andExpect(status().isBadRequest());
    }
}
