package com.filemaster.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a plain, normal Word (.docx) file: real paragraphs and inline pictures, no text boxes.
 * Only the markup that Word, LibreOffice and Google Docs all understand is used.
 */
final class DocxBuilder {

    /** One piece of text with its own look. */
    record RunSpec(String text, boolean bold, boolean italic, double sizePt, String font, String color) {
    }

    sealed interface Block permits ParaBlock, PictureBlock {
    }

    /** A paragraph. align: "left" or "center". */
    record ParaBlock(List<RunSpec> runs, String align, int leftTwips, int hangingTwips, int beforeTwips,
                     boolean pageBreakBefore) implements Block {
    }

    /** A picture on its own line. Sizes are in points. */
    record PictureBlock(Path file, double widthPt, double heightPt, String align, int leftTwips, int beforeTwips,
                        boolean pageBreakBefore) implements Block {
    }

    private static final Pattern INVALID_XML = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]");

    private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
            + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
            + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
            + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"";

    private DocxBuilder() {
    }

    static void write(Path out, int pageWidthTwips, int pageHeightTwips,
                      int marginLeft, int marginRight, int marginTop, int marginBottom,
                      List<Block> blocks) throws IOException {
        StringBuilder body = new StringBuilder();
        List<Path> media = new ArrayList<>();
        int pictureId = 1;

        for (Block block : blocks) {
            if (block instanceof ParaBlock p) {
                body.append(paragraph(p));
            } else if (block instanceof PictureBlock pic) {
                media.add(pic.file());
                body.append(picture(pic, media.size(), pictureId++));
            }
        }
        if (blocks.isEmpty()) {
            body.append("<w:p/>");
        }
        body.append("<w:sectPr><w:pgSz w:w=\"").append(pageWidthTwips).append("\" w:h=\"").append(pageHeightTwips)
                .append("\"/><w:pgMar w:top=\"").append(marginTop).append("\" w:right=\"").append(marginRight)
                .append("\" w:bottom=\"").append(marginBottom).append("\" w:left=\"").append(marginLeft)
                .append("\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>");

        try (OutputStream file = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(file)) {
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>");
            put(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document " + NS + "><w:body>" + body + "</w:body></w:document>");
            put(zip, "word/styles.xml", styles());
            put(zip, "word/_rels/document.xml.rels", documentRelationships(media));
            for (int i = 0; i < media.size(); i++) {
                zip.putNextEntry(new ZipEntry("word/media/" + mediaName(media.get(i), i + 1)));
                Files.copy(media.get(i), zip);
                zip.closeEntry();
            }
        }
    }

    // ---------- paragraphs and pictures ----------

    private static String paragraph(ParaBlock p) {
        StringBuilder xml = new StringBuilder("<w:p><w:pPr>");
        if (p.pageBreakBefore()) {
            xml.append("<w:pageBreakBefore/>");
        }
        xml.append("<w:spacing w:before=\"").append(Math.max(0, p.beforeTwips())).append("\" w:after=\"0\"/>");
        if (p.leftTwips() > 0 || p.hangingTwips() > 0) {
            xml.append("<w:ind w:left=\"").append(Math.max(0, p.leftTwips())).append("\"");
            if (p.hangingTwips() > 0) {
                xml.append(" w:hanging=\"").append(p.hangingTwips()).append("\"");
            }
            xml.append("/>");
        }
        if ("center".equals(p.align())) {
            xml.append("<w:jc w:val=\"center\"/>");
        }
        xml.append("</w:pPr>");
        for (RunSpec run : p.runs()) {
            xml.append(run(run));
        }
        return xml.append("</w:p>").toString();
    }

    /** Splits a run where mathematical letters (like the italic I, K, m, n in formulas) start or end. */
    private static String run(RunSpec r) {
        String text = r.text();
        StringBuilder out = new StringBuilder();
        int start = 0;
        boolean math = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            boolean isMath = cp >= 0x1D400 && cp <= 0x1D7FF;
            if (i > start && isMath != math) {
                out.append(plainRun(r, text.substring(start, i), math ? "Cambria Math" : r.font()));
                start = i;
            }
            math = isMath;
            i += Character.charCount(cp);
        }
        out.append(plainRun(r, text.substring(start), math ? "Cambria Math" : r.font()));
        return out.toString();
    }

    private static String plainRun(RunSpec r, String text, String fontName) {
        StringBuilder xml = new StringBuilder("<w:r><w:rPr>");
        if (fontName != null && !fontName.isBlank()) {
            String font = escape(fontName);
            xml.append("<w:rFonts w:ascii=\"").append(font).append("\" w:hAnsi=\"").append(font)
                    .append("\" w:cs=\"").append(font).append("\"/>");
        }
        if (r.bold()) {
            xml.append("<w:b/><w:bCs/>");
        }
        if (r.italic()) {
            xml.append("<w:i/><w:iCs/>");
        }
        if (r.color() != null && r.color().matches("[0-9A-Fa-f]{6}") && !r.color().equals("000000")) {
            xml.append("<w:color w:val=\"").append(r.color().toUpperCase(Locale.ROOT)).append("\"/>");
        }
        int halfPoints = (int) Math.round(Math.max(4, Math.min(144, r.sizePt())) * 2);
        xml.append("<w:sz w:val=\"").append(halfPoints).append("\"/><w:szCs w:val=\"").append(halfPoints).append("\"/>");
        xml.append("</w:rPr>");

        String[] parts = INVALID_XML.matcher(text).replaceAll("").split("\t", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                xml.append("<w:tab/>");
            }
            if (!parts[i].isEmpty()) {
                xml.append("<w:t xml:space=\"preserve\">").append(escape(parts[i])).append("</w:t>");
            }
        }
        return xml.append("</w:r>").toString();
    }

    private static String picture(PictureBlock pic, int mediaNumber, int id) {
        long cx = Math.round(pic.widthPt() * 12700);    // 1 point = 12700 EMU
        long cy = Math.round(pic.heightPt() * 12700);
        String name = mediaName(pic.file(), mediaNumber);
        String relationship = "rId" + (mediaNumber + 1);

        StringBuilder xml = new StringBuilder("<w:p><w:pPr>");
        if (pic.pageBreakBefore()) {
            xml.append("<w:pageBreakBefore/>");
        }
        xml.append("<w:spacing w:before=\"").append(Math.max(0, pic.beforeTwips())).append("\" w:after=\"0\"/>");
        if (pic.leftTwips() > 0) {
            xml.append("<w:ind w:left=\"").append(pic.leftTwips()).append("\"/>");
        }
        if ("center".equals(pic.align())) {
            xml.append("<w:jc w:val=\"center\"/>");
        }
        xml.append("</w:pPr><w:r><w:drawing>")
                .append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
                .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                .append("<wp:docPr id=\"").append(id).append("\" name=\"Picture ").append(id).append("\"/>")
                .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
                .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                .append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(id).append("\" name=\"").append(escape(name))
                .append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
                .append("<pic:blipFill><a:blip r:embed=\"").append(relationship).append("\"/>")
                .append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy)
                .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
                .append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
        return xml.toString();
    }

    // ---------- package parts ----------

    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>"
                + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                + "<Default Extension=\"gif\" ContentType=\"image/gif\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "</Types>";
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>"
                + "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
                + "</w:styles>";
    }

    private static String documentRelationships(List<Path> media) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        for (int i = 0; i < media.size(); i++) {
            xml.append("<Relationship Id=\"rId").append(i + 2)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/")
                    .append(mediaName(media.get(i), i + 1)).append("\"/>");
        }
        return xml.append("</Relationships>").toString();
    }

    private static String mediaName(Path file, int number) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = name.endsWith(".png") ? "png" : name.endsWith(".gif") ? "gif" : "jpg";
        return "image" + number + "." + extension;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
