package com.filemaster.service;

import com.filemaster.config.FileTypes;
import com.filemaster.exception.FileMasterException;
import com.filemaster.model.FileResult;
import com.filemaster.model.FormatsResponse;
import com.filemaster.model.Para;
import com.filemaster.model.UploadInfo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real conversion: content is decoded and re-encoded, never just renamed.
 *
 * Two kinds of conversion:
 *  - images (JPG/PNG): decoded to pixels and written in the other format
 *  - documents (TXT/DOC/DOCX/PDF):
 *      * with LibreOffice installed (normal case): the whole document is converted, so pictures,
 *        logos, tables, headers, fonts and non-English text are kept;
 *      * without LibreOffice: only the text can be converted, and the user is told so.
 *    Conversions TO plain text (TXT) are always done by our own code, because a text file
 *    cannot hold pictures anyway. The user is told how many pictures were left out.
 *
 * Merging: every document is converted to PDF, the PDFs are joined, and (if wanted) the joined
 * PDF is converted to DOCX. Nothing is dropped on the way.
 */
@Service
public class ConversionService {

    /** file extension -> format name */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "jpg", "JPG", "jpeg", "JPG", "png", "PNG",
            "txt", "TXT", "docx", "DOCX", "doc", "DOC", "pdf", "PDF");

    /** source format -> formats it can be converted to. */
    private static final Map<String, List<String>> SUPPORTED = Map.of(
            "JPG", List.of("PNG"),
            "PNG", List.of("JPG"),
            "TXT", List.of("PDF", "DOCX"),
            "DOCX", List.of("PDF", "TXT"),
            "DOC", List.of("PDF", "TXT", "DOCX"),
            "PDF", List.of("DOCX"));

    /** Formats whose files can be merged into one output (images cannot). */
    private static final List<String> MERGEABLE = List.of("TXT", "DOC", "DOCX", "PDF");

    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;
    private static final String FLOW_NOTE = "Text is rebuilt as normal paragraphs and pictures stay in place. "
            + "Tables, columns and drawn (vector) graphics may need small adjustments in Word.";
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private record DocSource(Path path, String format, String displayName) {
    }

    private record ReadResult(List<Para> paragraphs, int pictures) {
    }

    private final FileValidationService validator;
    private final TempFileService tempFiles;
    private final OfficeService office;
    private final PopplerService poppler;

    public ConversionService(FileValidationService validator, TempFileService tempFiles, OfficeService office,
                             PopplerService poppler) {
        this.validator = validator;
        this.tempFiles = tempFiles;
        this.office = office;
        this.poppler = poppler;
    }

    public FormatsResponse formats() {
        return new FormatsResponse(EXTENSIONS, SUPPORTED, MERGEABLE);
    }

    // ---------- one file ----------

    public FileResult convert(MultipartFile file, String targetFormat) {
        UploadInfo info = validator.validate(file, FileTypes.CONVERTIBLE);
        String source = formatOf(info.extension());
        String target = normalize(targetFormat);
        requireSupported(source, target);

        return tempFiles.withWorkDir(workDir -> {
            Path input = tempFiles.saveUpload(file, workDir, "input." + info.extension());
            long originalSize = Files.size(input);

            return switch (source + "_" + target) {
                case "JPG_PNG" -> imageToPng(input, workDir, info, originalSize);
                case "PNG_JPG" -> imageToJpg(input, workDir, info, originalSize);
                default -> convertDocuments(
                        List.of(new DocSource(input, source, info.baseName() + "." + info.extension())),
                        target, workDir, info.baseName(), originalSize, false);
            };
        });
    }

    // ---------- several files merged into one ----------

    public FileResult convertMerged(List<MultipartFile> files, String targetFormat) {
        List<UploadInfo> infos = validator.validateAll(files, FileTypes.CONVERTIBLE);
        if (files.size() < 2) {
            throw FileMasterException.badRequest("Choose at least two files to merge.");
        }
        String target = normalize(targetFormat);
        for (UploadInfo info : infos) {
            String source = formatOf(info.extension());
            if (!MERGEABLE.contains(source)) {
                throw FileMasterException.badRequest(
                        "Images cannot be merged into one file. Convert them one by one instead.");
            }
            requireSupported(source, target);
        }

        return tempFiles.withWorkDir(workDir -> {
            List<DocSource> sources = new ArrayList<>();
            long originalSize = 0;
            for (int i = 0; i < files.size(); i++) {
                UploadInfo info = infos.get(i);
                Path input = tempFiles.saveUpload(files.get(i), workDir, "input-" + i + "." + info.extension());
                originalSize += Files.size(input);
                sources.add(new DocSource(input, formatOf(info.extension()), info.baseName() + "." + info.extension()));
            }
            return convertDocuments(sources, target, workDir, "filemaster-merged", originalSize, true);
        });
    }

    // ---------- image conversions ----------

    private FileResult imageToPng(Path input, Path workDir, UploadInfo info, long originalSize) throws IOException {
        Path out = Files.createTempFile(workDir, "output-", ".png");
        ImageHelper.writePng(ImageHelper.read(input), out);
        return result(out, info.baseName() + ".png", "image/png", originalSize, null);
    }

    private FileResult imageToJpg(Path input, Path workDir, UploadInfo info, long originalSize) throws IOException {
        BufferedImage image = ImageHelper.read(input);
        // JPG cannot store transparency. Say so instead of silently changing the picture.
        String note = image.getColorModel().hasAlpha()
                ? "JPG cannot store transparency, so transparent areas were filled with white. Choose PNG to keep transparency."
                : null;
        Path out = Files.createTempFile(workDir, "output-", ".jpg");
        ImageHelper.writeJpeg(image, out, 0.92f);
        return result(out, info.baseName() + ".jpg", "image/jpeg", originalSize, note);
    }

    // ---------- document conversions (single or merged) ----------

    private FileResult convertDocuments(List<DocSource> sources, String target, Path workDir,
                                        String outBaseName, long originalSize, boolean merged) throws IOException {
        boolean onlyPdfs = sources.stream().allMatch(s -> s.format().equals("PDF"));
        if (target.equals("DOCX") && onlyPdfs && poppler.isAvailable()) {
            // PDF to Word: real paragraphs and pictures in place (fast, and Word lays it out correctly)
            try {
                Path pdf = merged ? joinPdfs(sources, workDir) : sources.get(0).path();
                Path docx = poppler.pdfToDocx(workDir, pdf);
                return result(docx, outBaseName + ".docx", DOCX_MIME, originalSize, FLOW_NOTE);
            } catch (FileMasterException e) {
                if (!office.isAvailable()) {
                    throw e;               // nothing else to try
                }                          // otherwise: try the LibreOffice route below
            }
        }
        if (office.isAvailable() && needsOffice(sources, target)) {
            return convertWithOffice(sources, target, workDir, outBaseName, originalSize, merged);
        }
        return convertTextOnly(sources, target, workDir, outBaseName, originalSize, merged);
    }

    /** Several PDFs -> one PDF (pages are copied, nothing is lost). */
    private Path joinPdfs(List<DocSource> sources, Path workDir) throws IOException {
        List<Path> pdfs = new ArrayList<>();
        for (DocSource source : sources) {
            pdfs.add(source.path());
        }
        Path joined = Files.createTempFile(workDir, "joined-", ".pdf");
        DocumentHelper.mergePdfs(pdfs, joined);
        return joined;
    }

    /** Which conversions are handed to LibreOffice (the rest are done by our own code). */
    private static boolean needsOffice(List<DocSource> sources, String target) {
        if (target.equals("TXT")) {
            return false;   // a text file cannot hold pictures, so reading the text ourselves loses nothing more
        }
        boolean onlyText = sources.stream().allMatch(s -> s.format().equals("TXT"));
        return !(target.equals("DOCX") && onlyText);   // plain text to Word: our own writer is exact
    }

    // ----- full-quality path: LibreOffice -----

    private FileResult convertWithOffice(List<DocSource> sources, String target, Path workDir,
                                         String outBaseName, long originalSize, boolean merged) throws IOException {
        Path output;
        String note = null;

        if (!merged) {
            DocSource source = sources.get(0);
            if (target.equals("PDF")) {
                output = toPdf(source, workDir, 0);
            } else if (source.format().equals("PDF")) {
                output = office.pdfToDocx(workDir, source.path());
                note = "The layout is rebuilt from the PDF, so everything stays where it was. "
                        + "In Word, text sits in boxes and spacing can differ slightly.";
            } else {
                output = office.docToDocx(workDir, source.path());   // DOC -> DOCX
            }
        } else {
            List<Path> pdfs = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                pdfs.add(toPdf(sources.get(i), workDir, i));
            }
            Path joined = Files.createTempFile(workDir, "merged-", ".pdf");
            DocumentHelper.mergePdfs(pdfs, joined);
            if (target.equals("PDF")) {
                output = joined;
            } else if (poppler.isAvailable()) {
                output = poppler.pdfToDocx(workDir, joined);
                note = "Files were joined as PDF first, then converted to Word. " + FLOW_NOTE;
            } else {
                output = office.pdfToDocx(workDir, joined);
                note = "Files were joined as PDF first, then converted to Word, so text sits in boxes in Word.";
            }
        }

        String extension = target.toLowerCase(Locale.ROOT);
        return result(output, outBaseName + "." + extension, mimeOf(target), originalSize, note);
    }

    private Path toPdf(DocSource source, Path workDir, int index) throws IOException {
        return switch (source.format()) {
            case "PDF" -> source.path();
            case "DOCX" -> office.docxToPdf(workDir, source.path());
            case "DOC" -> office.docToPdf(workDir, source.path());
            case "TXT" -> office.txtToPdf(workDir, utf8Copy(source, workDir, index));
            default -> throw FileMasterException.badRequest("This file type cannot be converted to PDF.");
        };
    }

    /** LibreOffice must be told the text is UTF-8, so we decode it ourselves and save a clean UTF-8 copy. */
    private Path utf8Copy(DocSource source, Path workDir, int index) throws IOException {
        checkTextSize(source);
        Path copy = workDir.resolve("text-" + index + ".txt");
        Files.writeString(copy, decodeText(Files.readAllBytes(source.path())), StandardCharsets.UTF_8);
        return copy;
    }

    // ----- text-only path: used for "to TXT", for text to Word, and when LibreOffice is missing -----

    private FileResult convertTextOnly(List<DocSource> sources, String target, Path workDir,
                                       String outBaseName, long originalSize, boolean merged) throws IOException {
        List<Para> all = new ArrayList<>();
        boolean needsEngineNote = false;
        int pictures = 0;

        for (int i = 0; i < sources.size(); i++) {
            DocSource source = sources.get(i);
            ReadResult read = readParagraphs(source, workDir);
            List<Para> paragraphs = read.paragraphs();
            pictures += read.pictures();
            if (!source.format().equals("TXT")) {
                needsEngineNote = true;
            }

            if (merged && target.equals("TXT")) {
                if (i > 0) {
                    all.add(Para.blank());
                }
                all.add(Para.plain("===== " + source.displayName() + " ====="));
                all.add(Para.blank());
            } else if (merged && i > 0 && !paragraphs.isEmpty()) {
                paragraphs.set(0, paragraphs.get(0).withPageBreak()); // each file starts on a new page
            }
            all.addAll(paragraphs);
        }

        String extension = target.toLowerCase(Locale.ROOT);
        Path out = Files.createTempFile(workDir, "output-", "." + extension);
        String note = null;

        if (target.equals("TXT")) {
            if (pictures > 0) {
                note = pictures + (pictures == 1 ? " picture" : " pictures")
                        + " in the original cannot be stored in a plain text file, so "
                        + (pictures == 1 ? "it was" : "they were") + " left out. Convert to PDF or DOCX to keep pictures.";
            }
        } else if (needsEngineNote) {
            note = "Text and pictures were converted, but page layout, tables and fonts are simplified because LibreOffice "
                    + "was not found. Install LibreOffice (libreoffice.org) and restart FileMaster for an exact copy.";
            if (sources.stream().anyMatch(s -> s.format().equals("DOC") || s.format().equals("PDF"))) {
                note += " Pictures of .doc and PDF files are only kept when LibreOffice (or poppler for PDF) is installed.";
            }
        }

        switch (target) {
            case "PDF" -> {
                int replaced = PdfHelper.write(all, out);
                if (replaced > 0) {
                    String warning = "Some characters are not supported by the PDF font and were replaced with '?'.";
                    note = note == null ? warning : note + " " + warning;
                }
            }
            case "DOCX" -> DocumentHelper.writeDocx(all, out);
            case "TXT" -> Files.writeString(out, DocumentHelper.toPlainText(all), StandardCharsets.UTF_8);
            default -> throw FileMasterException.badRequest("This conversion is not supported yet.");
        }

        return result(out, outBaseName + "." + extension, mimeOf(target), originalSize, note);
    }

    private ReadResult readParagraphs(DocSource source, Path workDir) throws IOException {
        return switch (source.format()) {
            case "TXT" -> {
                checkTextSize(source);
                yield new ReadResult(DocumentHelper.fromText(decodeText(Files.readAllBytes(source.path()))), 0);
            }
            case "DOCX" -> new ReadResult(DocumentHelper.fromDocx(source.path()), DocxTextReader.pictureCount(source.path()));
            case "DOC" -> readDoc(source, workDir);
            case "PDF" -> new ReadResult(DocumentHelper.fromPdf(source.path()), 0);
            default -> throw FileMasterException.badRequest("This file type cannot be read as a document.");
        };
    }

    /** Old .doc files: with LibreOffice they are turned into .docx first (keeps text boxes, counts pictures). */
    private ReadResult readDoc(DocSource source, Path workDir) throws IOException {
        if (office.isAvailable()) {
            Path docx = office.docToDocx(workDir, source.path());
            return new ReadResult(DocumentHelper.fromDocx(docx), DocxTextReader.pictureCount(docx));
        }
        return new ReadResult(DocumentHelper.fromDoc(source.path()), 0);
    }

    // ---------- small helpers ----------

    private static void checkTextSize(DocSource source) throws IOException {
        if (Files.size(source.path()) > MAX_TEXT_BYTES) {
            throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "A text file is too large to convert (maximum 2 MB per text file).");
        }
    }

    private FileResult result(Path out, String fileName, String mime, long originalSize, String note)
            throws IOException {
        byte[] data = Files.readAllBytes(out);
        return new FileResult(data, fileName, mime, originalSize, data.length, note);
    }

    private static void requireSupported(String source, String target) {
        if (!SUPPORTED.getOrDefault(source, List.of()).contains(target)) {
            throw FileMasterException.badRequest("Converting " + source + " to "
                    + (target.isEmpty() ? "that format" : target) + " is not supported.");
        }
    }

    private static String normalize(String format) {
        return format == null ? "" : format.trim().toUpperCase(Locale.ROOT);
    }

    private static String formatOf(String extension) {
        return EXTENSIONS.getOrDefault(extension, extension.toUpperCase(Locale.ROOT));
    }

    private static String mimeOf(String target) {
        return switch (target) {
            case "PDF" -> "application/pdf";
            case "DOCX" -> DOCX_MIME;
            default -> "text/plain; charset=UTF-8";
        };
    }

    /** UTF-8 first; if the bytes are not valid UTF-8, fall back to Latin-1 (old Windows text files). */
    private static String decodeText(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, StandardCharsets.ISO_8859_1);
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}
