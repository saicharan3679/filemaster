package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import com.filemaster.model.Para;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads ALL text of a .docx by walking its XML in document order:
 * paragraphs, table rows, text boxes (which other readers often skip), headers and footers.
 * Only text can be returned, so pictures are counted separately by pictureCount().
 */
final class DocxTextReader {

    private static final Pattern INVALID_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]");
    private static final long MAX_XML_BYTES = 200L * 1024 * 1024;
    private static final String CELL_SEPARATOR = "   |   ";

    private DocxTextReader() {
    }

    /** Headers first, then the body, then footers. */
    static List<Para> read(java.nio.file.Path docx) {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            ZipEntry body = zip.getEntry("word/document.xml");
            if (body == null) {
                throw invalid();
            }
            Set<String> headers = new LinkedHashSet<>();
            Set<String> footers = new LinkedHashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.matches("word/header\\d*\\.xml")) {
                    collectText(zip, entry, headers);
                } else if (name.matches("word/footer\\d*\\.xml")) {
                    collectText(zip, entry, footers);
                }
            }

            List<Para> result = new ArrayList<>();
            for (String line : headers) {
                result.add(Para.plain(line));
            }
            if (!headers.isEmpty()) {
                result.add(Para.blank());
            }
            result.addAll(parse(zip, body));
            if (!footers.isEmpty()) {
                result.add(Para.blank());
            }
            for (String line : footers) {
                result.add(Para.plain(line));
            }
            return result;
        } catch (IOException | XMLStreamException e) {
            throw invalid();
        }
    }

    /** Number of picture files stored inside the .docx (logos, photos, ...). */
    static int pictureCount(java.nio.file.Path docx) {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            int count = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith("word/media/")) {
                    count++;
                }
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------- XML walking ----------

    private static void collectText(ZipFile zip, ZipEntry entry, Set<String> into) throws IOException, XMLStreamException {
        for (Para para : parse(zip, entry)) {
            if (!para.text().isBlank()) {
                into.add(para.text());
            }
        }
    }

    private static final class ParaState {
        final StringBuilder text = new StringBuilder();
        boolean heading;
        boolean allBold = true;
        boolean hasText;
    }

    private static List<Para> parse(ZipFile zip, ZipEntry entry) throws IOException, XMLStreamException {
        if (entry.getSize() > MAX_XML_BYTES) {
            throw invalid();
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);                    // no XML entity tricks
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);

        List<Para> result = new ArrayList<>();
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            Deque<ParaState> paragraphs = new ArrayDeque<>();
            Deque<List<String>> rows = new ArrayDeque<>();
            Deque<StringBuilder> cells = new ArrayDeque<>();
            int inParagraphProperties = 0;
            boolean inRun = false;
            boolean runBold = false;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    switch (name) {
                        case "Fallback" -> skipElement(reader);   // a duplicate copy of a text box, for old Word versions
                        case "p" -> paragraphs.push(new ParaState());
                        case "pPr" -> inParagraphProperties++;
                        case "pStyle" -> {
                            String style = reader.getAttributeValue(null, "val");
                            if (style == null && reader.getAttributeCount() > 0) {
                                style = reader.getAttributeValue(0);
                            }
                            if (!paragraphs.isEmpty() && style != null
                                    && (style.startsWith("Heading") || style.equalsIgnoreCase("Title"))) {
                                paragraphs.peek().heading = true;
                            }
                        }
                        case "r" -> {
                            inRun = true;
                            runBold = false;
                        }
                        case "b" -> {
                            String value = reader.getAttributeCount() > 0 ? reader.getAttributeValue(0) : null;
                            if (inRun && inParagraphProperties == 0 && !"0".equals(value) && !"false".equals(value)) {
                                runBold = true;
                            }
                        }
                        case "t" -> {
                            String text = reader.getElementText();
                            ParaState state = paragraphs.peek();
                            if (state != null) {
                                state.text.append(text);
                                if (!text.isBlank()) {
                                    state.hasText = true;
                                    if (!runBold) {
                                        state.allBold = false;
                                    }
                                }
                            }
                        }
                        case "tab" -> {
                            if (!paragraphs.isEmpty()) {
                                paragraphs.peek().text.append('\t');
                            }
                        }
                        case "br", "cr" -> {
                            if (!paragraphs.isEmpty()) {
                                paragraphs.peek().text.append('\n');
                            }
                        }
                        case "tr" -> rows.push(new ArrayList<>());
                        case "tc" -> cells.push(new StringBuilder());
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    switch (name) {
                        case "pPr" -> inParagraphProperties--;
                        case "r" -> inRun = false;
                        case "p" -> {
                            ParaState state = paragraphs.pop();
                            String text = clean(state.text.toString());
                            if (!cells.isEmpty()) {
                                if (!text.isBlank()) {
                                    StringBuilder cell = cells.peek();
                                    cell.append(cell.length() > 0 ? " " : "").append(text.strip());
                                }
                            } else if (!text.isBlank()) {
                                result.add(new Para(text, state.heading || (state.hasText && state.allBold), false));
                            } else if (paragraphs.isEmpty()) {
                                result.add(Para.blank());
                            }
                        }
                        case "tc" -> {
                            StringBuilder cell = cells.pop();
                            if (!rows.isEmpty()) {
                                rows.peek().add(cell.toString());
                            }
                        }
                        case "tr" -> {
                            List<String> row = rows.pop();
                            String line = String.join(CELL_SEPARATOR, row);
                            if (!line.replace(CELL_SEPARATOR, "").isBlank()) {
                                result.add(Para.plain(line));
                            }
                        }
                        case "tbl" -> result.add(Para.blank());
                        default -> { }
                    }
                }
            }
            reader.close();
        }
        return result;
    }

    private static void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static String clean(String text) {
        return INVALID_CHARS.matcher(text).replaceAll("");
    }

    private static FileMasterException invalid() {
        return FileMasterException.badRequest("This file is not a valid Word (.docx) document.");
    }
}
