package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Makes an uploaded .docx safe before LibreOffice opens it.
 *
 * A Word file can contain a picture, template or object that points to a web address
 * or a file on the server. LibreOffice would download or read it, which would let an
 * uploaded file make OUR server contact other computers. (This was tested: without this
 * step LibreOffice does fetch a linked picture.)
 *
 * So every "external" relationship, except ordinary clickable hyperlinks, is removed.
 * Pictures that are INSIDE the document are not touched. A file that is not really a
 * .docx (for example an ODT renamed to .docx) is refused.
 */
final class OfficeSanitizer {

    private static final long MAX_UNPACKED_BYTES = 300L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_RELS_BYTES = 5 * 1024 * 1024;
    private static final Pattern URL_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*:");

    private OfficeSanitizer() {
    }

    static void cleanDocx(Path input, Path output) throws IOException {
        try (ZipFile zip = new ZipFile(input.toFile());
             OutputStream file = Files.newOutputStream(output);
             ZipOutputStream out = new ZipOutputStream(file)) {

            if (zip.getEntry("word/document.xml") == null || zip.getEntry("[Content_Types].xml") == null) {
                throw notDocx();
            }

            long total = 0;
            int entries = 0;
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("..")) {
                    continue; // never copy strange paths
                }
                if (++entries > MAX_ENTRIES) {
                    throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE, "This document contains too many parts.");
                }

                out.putNextEntry(new ZipEntry(name));
                try (InputStream in = zip.getInputStream(entry)) {
                    if (name.endsWith(".rels")) {
                        byte[] xml = in.readNBytes(MAX_RELS_BYTES + 1);
                        if (xml.length > MAX_RELS_BYTES) {
                            throw notDocx();
                        }
                        byte[] cleaned = withoutExternalLinks(xml);
                        total += cleaned.length;
                        out.write(cleaned);
                    } else {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            total += read;
                            if (total > MAX_UNPACKED_BYTES) {
                                throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE,
                                        "This document is too large once unpacked.");
                            }
                            out.write(buffer, 0, read);
                        }
                    }
                }
                out.closeEntry();
            }
        } catch (ZipException e) {
            throw notDocx();
        }
    }

    /** Removes every relationship that points outside the file, except hyperlinks. */
    static byte[] withoutExternalLinks(byte[] relsXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // no XML entity tricks
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(relsXml));

            NodeList nodes = document.getElementsByTagNameNS("*", "Relationship");
            List<Element> remove = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element relationship = (Element) nodes.item(i);
                boolean hyperlink = relationship.getAttribute("Type").endsWith("/hyperlink");
                boolean external = "external".equalsIgnoreCase(relationship.getAttribute("TargetMode"))
                        || URL_SCHEME.matcher(relationship.getAttribute("Target").trim()).find();
                if (external && !hyperlink) {
                    remove.add(relationship);
                }
            }
            for (Element element : remove) {
                element.getParentNode().removeChild(element);
            }

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(result));
            return result.toByteArray();
        } catch (ParserConfigurationException | SAXException | IOException | TransformerException e) {
            // If we cannot read it safely, we do not hand it to LibreOffice at all.
            throw FileMasterException.badRequest("This document could not be checked safely and was refused.");
        }
    }

    private static FileMasterException notDocx() {
        return FileMasterException.badRequest("This file is not a valid Word (.docx) document.");
    }
}
