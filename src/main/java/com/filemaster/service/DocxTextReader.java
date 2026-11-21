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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
            Map<String, String> pictureFiles = relationships(zip, "word/_rels/document.xml.rels");
            Set<String> headers = new LinkedHashSet<>();
            Set<String> footers = new LinkedHashSet<>();
            List<Para> headerPictures = new ArrayList<>();
            List<Para> footerPictures = new ArrayList<>();
            Set<Integer> seenPictures = new HashSet<>();      // the same logo is often in every header: keep it once
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.matches("word/header\\d*\\.xml")) {
                    collect(zip, entry, headers, headerPictures, seenPictures);
                } else if (name.matches("word/footer\\d*\\.xml")) {
                    collect(zip, entry, footers, footerPictures, seenPictures);
                }
            }

            List<Para> result = new ArrayList<>(headerPictures);
            for (String line : headers) {
                result.add(Para.plain(line));
            }
            if (!headers.isEmpty() || !headerPictures.isEmpty()) {
                result.add(Para.blank());
            }
            result.addAll(parse(zip, body, pictureFiles));
            if (!footers.isEmpty() || !footerPictures.isEmpty()) {
                result.add(Para.blank());
            }
            for (String line : footers) {
                result.add(Para.plain(line));
            }
            result.addAll(footerPictures);
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

    /** Text lines and pictures of one header or footer part (each part has its own picture relationships). */
    private static void collect(ZipFile zip, ZipEntry entry, Set<String> lines, List<Para> pictures, Set<Integer> seen)
            throws IOException, XMLStreamException {
        String file = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
        Map<String, String> pictureFiles = relationships(zip, "word/_rels/" + file + ".rels");
        for (Para para : parse(zip, entry, pictureFiles)) {
            if (para.picture() != null) {
                if (seen.add(java.util.Arrays.hashCode(para.picture().data()))) {
                    pictures.add(para);
                }
            } else if (!para.text().isBlank()) {
                lines.add(para.text());
            }
        }
    }

    private static final class ParaState {
        final StringBuilder text = new StringBuilder();
        boolean heading;
        boolean allBold = true;
        boolean hasText;
        final List<Para.Picture> pictures = new ArrayList<>();
    }

    /** @param pictureFiles relationship id -> picture file (null: ignore pictures, used for headers and footers) */
    private static List<Para> parse(ZipFile zip, ZipEntry entry, Map<String, String> pictureFiles)
            throws IOException, XMLStreamException {
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
            long extentCx = 0;
            long extentCy = 0;
            long pictureBytes = 0;
            List<Para> afterTable = new ArrayList<>();   // pictures found inside table cells, placed after the table

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
                        case "extent" -> {                     // the size of a picture (in EMU, 12700 = 1 point)
                            extentCx = longAttribute(reader, "cx");
                            extentCy = longAttribute(reader, "cy");
                        }
                        case "blip" -> {                       // a picture: r:embed points to the picture file
                            String id = reader.getAttributeValue(null, "embed");
                            ParaState state = paragraphs.peek();
                            if (pictureFiles != null && id != null && state != null && pictureFiles.containsKey(id)) {
                                byte[] data = readPicture(zip, pictureFiles.get(id), MAX_PICTURE_BYTES);
                                String extension = extensionOf(pictureFiles.get(id));
                                if (data != null && SUPPORTED_PICTURES.contains(extension)
                                        && pictureBytes + data.length <= MAX_TOTAL_PICTURE_BYTES) {
                                    pictureBytes += data.length;
                                    state.pictures.add(new Para.Picture(data, extension, extentCx / 12700.0, extentCy / 12700.0));
                                }
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
                                for (Para.Picture picture : state.pictures) {
                                    afterTable.add(Para.ofPicture(picture));
                                }
                            } else {
                                if (!text.isBlank()) {
                                    result.add(new Para(text, state.heading || (state.hasText && state.allBold), false));
                                } else if (paragraphs.isEmpty() && state.pictures.isEmpty()) {
                                    result.add(Para.blank());
                                }
                                for (Para.Picture picture : state.pictures) {
                                    result.add(Para.ofPicture(picture));
                                }
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
                        case "tbl" -> {
                            result.addAll(afterTable);
                            afterTable.clear();
                            result.add(Para.blank());
                        }
                        default -> { }
                    }
                }
            }
            reader.close();
        }
        return result;
    }

    // ---------- pictures ----------

    private static final Set<String> SUPPORTED_PICTURES = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    private static final int MAX_PICTURE_BYTES = 20 * 1024 * 1024;
    private static final long MAX_TOTAL_PICTURE_BYTES = 60L * 1024 * 1024;

    /** relationship id -> "media/image1.png" for every relationship of the main document part. */
    private static Map<String, String> relationships(ZipFile zip, String relsPath) {
        Map<String, String> map = new HashMap<>();
        ZipEntry entry = zip.getEntry(relsPath);
        if (entry == null) {
            return map;
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("Relationship")) {
                    String id = reader.getAttributeValue(null, "Id");
                    String target = reader.getAttributeValue(null, "Target");
                    String mode = reader.getAttributeValue(null, "TargetMode");
                    if (id != null && target != null && !"External".equalsIgnoreCase(mode) && !target.contains(":")) {
                        map.put(id, target);
                    }
                }
            }
            reader.close();
        } catch (IOException | XMLStreamException e) {
            return map;
        }
        return map;
    }

    /** Reads one picture file of the .docx. Only files inside "word/" are read. Returns null if it cannot be read. */
    private static byte[] readPicture(ZipFile zip, String target, int maxBytes) {
        String path = target.startsWith("/") ? target.substring(1) : "word/" + target;
        path = java.nio.file.Path.of(path).normalize().toString().replace('\\', '/');
        if (!path.startsWith("word/")) {
            return null;
        }
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory() || entry.getSize() > maxBytes) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] data = in.readNBytes(maxBytes + 1);
            return data.length > maxBytes ? null : data;
        } catch (IOException e) {
            return null;
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static long longAttribute(XMLStreamReader reader, String name) {
        try {
            String value = reader.getAttributeValue(null, name);
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
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
