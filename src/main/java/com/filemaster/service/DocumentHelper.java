package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import com.filemaster.model.Para;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads TXT, DOCX, DOC and PDF files into a list of paragraphs (Para),
 * and writes paragraphs out as DOCX or plain text.
 * Only text is handled: images, colours and page layout are not kept.
 */
final class DocumentHelper {

    static {
        // Protects against "zip bombs": a small DOCX that expands to gigabytes.
        ZipSecureFile.setMaxEntrySize(60L * 1024 * 1024);
    }

    private static final int MAX_PDF_PAGES = 300;

    /** Characters that are illegal in XML (and therefore in DOCX files). */
    private static final Pattern INVALID_XML = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]");

    private DocumentHelper() {
    }

    // ---------- reading ----------

    static List<Para> fromText(String text) {
        List<Para> result = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            result.add(Para.plain(clean(line.replace("\t", "    "))));
        }
        return trimBlankEnds(result);
    }

    /** All text of a .docx: body, tables, text boxes, headers and footers (see DocxTextReader). */
    static List<Para> fromDocx(Path file) {
        return finishDocument(trimBlankEnds(DocxTextReader.read(file)), "Word document");
    }

    static List<Para> fromDoc(Path file) {
        List<Para> result = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); HWPFDocument doc = new HWPFDocument(in)) {
            WordExtractor extractor = new WordExtractor(doc);
            for (String paragraph : extractor.getParagraphText()) {
                result.add(Para.plain(clean(paragraph.replace("\r", "")).stripTrailing()));
            }
        } catch (IOException | RuntimeException e) {
            throw FileMasterException.badRequest("This file is not a valid Word (.doc) document.");
        }
        return finishDocument(result, "Word document");
    }

    static List<Para> fromPdf(Path file) {
        List<Para> result = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            int pages = doc.getNumberOfPages();
            if (pages > MAX_PDF_PAGES) {
                throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "This PDF has too many pages. The limit is " + MAX_PDF_PAGES + " pages.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                List<Para> lines = new ArrayList<>();
                for (String line : stripper.getText(doc).lines().toList()) {
                    lines.add(Para.plain(clean(line).stripTrailing()));
                }
                lines = trimBlankEnds(lines);
                if (lines.isEmpty()) {
                    continue;
                }
                if (!result.isEmpty()) {
                    lines.set(0, lines.get(0).withPageBreak());
                }
                result.addAll(lines);
            }
        } catch (InvalidPasswordException e) {
            throw FileMasterException.badRequest("This PDF is password protected and cannot be converted.");
        } catch (IOException e) {
            throw FileMasterException.badRequest("This PDF could not be read. It may be damaged.");
        }
        if (result.isEmpty()) {
            throw FileMasterException.badRequest(
                    "No text was found in this PDF. Scanned PDFs (pictures of pages) are not supported.");
        }
        return result;
    }

    // ---------- merging PDFs ----------

    /** Joins PDF files one after the other. Pages are copied as they are, so nothing is lost. */
    static void mergePdfs(List<Path> pdfs, Path out) throws IOException {
        List<PDDocument> opened = new ArrayList<>();
        try (PDDocument merged = new PDDocument()) {
            try {
                for (Path pdf : pdfs) {
                    PDDocument source = Loader.loadPDF(pdf.toFile());
                    opened.add(source);                       // must stay open until the merged file is saved
                    for (PDPage page : source.getPages()) {
                        merged.importPage(page);
                    }
                }
                merged.save(out.toFile());
            } finally {
                for (PDDocument source : opened) {
                    source.close();
                }
            }
        }
    }

    // ---------- writing ----------

    static void writeDocx(List<Para> paragraphs, Path out) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); OutputStream os = Files.newOutputStream(out)) {
            for (Para para : paragraphs) {
                if (para.picture() != null) {
                    continue;                          // pictures are only written into PDF files (see PdfHelper)
                }
                XWPFParagraph paragraph = doc.createParagraph();
                if (para.pageBreakBefore()) {
                    paragraph.setPageBreak(true);
                }
                if (!para.text().isEmpty()) {
                    XWPFRun run = paragraph.createRun();
                    run.setBold(para.bold());
                    run.setFontSize(11);
                    run.setText(para.text());
                }
            }
            if (paragraphs.isEmpty()) {
                doc.createParagraph();
            }
            doc.write(os);
        }
    }

    static String toPlainText(List<Para> paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (Para para : paragraphs) {
            if (para.picture() != null) {
                continue;                              // a text file cannot hold pictures
            }
            if (para.pageBreakBefore() && sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(para.text()).append('\n');
        }
        return sb.toString();
    }

    // ---------- small helpers ----------

    private static List<Para> finishDocument(List<Para> paragraphs, String what) {
        List<Para> trimmed = trimBlankEnds(paragraphs);
        if (trimmed.isEmpty()) {
            throw FileMasterException.badRequest("This " + what + " contains no text.");
        }
        return trimmed;
    }

    /** Removes empty paragraphs at the start and end of a list. */
    private static List<Para> trimBlankEnds(List<Para> paragraphs) {
        int start = 0;
        int end = paragraphs.size();
        while (start < end && paragraphs.get(start).isEmpty()) {
            start++;
        }
        while (end > start && paragraphs.get(end - 1).isEmpty()) {
            end--;
        }
        return new ArrayList<>(paragraphs.subList(start, end));
    }

    private static String clean(String text) {
        return text == null ? "" : INVALID_XML.matcher(text).replaceAll("");
    }
}
