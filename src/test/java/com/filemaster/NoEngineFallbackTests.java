package com.filemaster;

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
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens on a computer where NEITHER LibreOffice NOR poppler is installed (both are switched off here, so this
 * test gives the same result everywhere). Pictures must still reach the PDF, and the user must be told what is simplified.
 */
@SpringBootTest(properties = {"filemaster.office-enabled=false", "filemaster.poppler-enabled=false"})
@AutoConfigureMockMvc
class NoEngineFallbackTests {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Autowired
    private MockMvc mockMvc;

    private static byte[] docxWithPicture() throws Exception {
        BufferedImage logo = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(new Color(28, 47, 70));
        g.fillRect(0, 0, 120, 60);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(logo, "png", png);

        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Text before the logo");
            XWPFRun run = doc.createParagraph().createRun();
            run.addPicture(new ByteArrayInputStream(png.toByteArray()), XWPFDocument.PICTURE_TYPE_PNG, "logo.png",
                    Units.toEMU(120), Units.toEMU(60));
            doc.createParagraph().createRun().setText("Text after the logo");
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void healthShowsThatBothEnginesAreOff() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.libreOffice").value(false))
                .andExpect(jsonPath("$.poppler").value(false));
    }

    @Test
    void pictureOfAWordFileStillReachesThePdf() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(multipart("/api/convert")
                        .file(new MockMultipartFile("file", "report.docx", "application/octet-stream", docxWithPicture()))
                        .param("targetFormat", "PDF"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        byte[] pdf = response.getContentAsByteArray();
        assertTrue(new String(pdf, 0, 4, StandardCharsets.US_ASCII).equals("%PDF"));
        assertTrue(Pattern.compile("/Subtype\\s*/Image").matcher(new String(pdf, StandardCharsets.ISO_8859_1)).find(),
                "the picture must be drawn into the PDF even without LibreOffice");
        String note = response.getHeader("X-Result-Note");
        assertTrue(note != null && note.contains("LibreOffice was not found"), "the user is told what is simplified: " + note);
    }
}
