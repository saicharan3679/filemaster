package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import org.springframework.http.HttpStatus;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the positioned text and pictures that "pdftohtml -xml" extracts from a PDF into a NORMAL Word document:
 * real paragraphs, bullet lists with hanging indents, headings kept bold and large, pictures in place, and
 * no text boxes. (LibreOffice's own PDF import puts every line in a floating text box, which Word lays out
 * badly: lines overlap or squeeze into half of the page.)
 *
 * The idea: every text line comes with its position and font. Lines that are close together and look alike are
 * joined into one paragraph, a new paragraph starts at a gap, a bullet, a heading or a short last line.
 *
 * Known limits (also in the README): tables become tab-separated lines, pages with several columns may be read in
 * the wrong order, and graphics drawn as vector shapes (not pictures) are not carried over.
 */
final class PdfFlowConverter {

    record Result(int pages, int paragraphs, int pictures, int characters) {
    }

    // ---------- what we read from the XML ----------

    /** size is in XML units (points x pxPerPoint). */
    private record FontSpec(double size, String family, String color) {
    }

    private record Parsed(List<PageData> pages, Map<Integer, FontSpec> fonts) {
    }

    private record Seg(String text, boolean bold, boolean italic) {
    }

    private static final class Item {
        double top, left, width, height;
        int font;
        final List<Seg> segs = new ArrayList<>();

        double bottom() {
            return top + height;
        }

        double right() {
            return left + width;
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (Seg s : segs) {
                sb.append(s.text());
            }
            return sb.toString();
        }
    }

    private record Pic(double top, double left, double width, double height, String src) {
    }

    private static final class PageData {
        final double width, height;
        final List<Item> items = new ArrayList<>();
        final List<Pic> pictures = new ArrayList<>();

        PageData(double width, double height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class Line {
        final List<Item> items = new ArrayList<>();
        double top, bottom, left, right, height;
        double refBottom;

        void add(Item item) {
            items.add(item);
            top = items.size() == 1 ? item.top : Math.min(top, item.top);
            bottom = items.size() == 1 ? item.bottom() : Math.max(bottom, item.bottom());
            left = items.size() == 1 ? item.left : Math.min(left, item.left);
            right = items.size() == 1 ? item.right() : Math.max(right, item.right());
            height = Math.max(height, item.height);
        }
    }

    private static final class Para {
        final List<Line> lines = new ArrayList<>();
        boolean bullet;
        String marker = "";
        double markerLeft, textLeft;

        double top() {
            return lines.get(0).top;
        }

        double bottom() {
            return lines.get(lines.size() - 1).bottom;
        }

        double minLeft() {
            double m = Double.MAX_VALUE;
            for (Line l : lines) {
                m = Math.min(m, l.left);
            }
            return m;
        }
    }

    private static final Pattern BULLET = Pattern.compile(
            "^([•▪■◦·●○▶➢➤◆◼✓✔–—]|-|\\*|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)])\\s+");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^(page\\s*)?\\d{1,4}(\\s*(of|/)\\s*\\d{1,4})?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBSET_PREFIX = Pattern.compile("^[A-Z]{6}\\+");
    private static final Pattern STYLE_SUFFIX = Pattern.compile("[-,](Bold|Italic|BoldItalic|BoldOblique|Oblique|Regular|Light|Medium|Semibold|Black)+$",
            Pattern.CASE_INSENSITIVE);

    private PdfFlowConverter() {
    }

    // =====================================================================
    // main entry
    // =====================================================================

    /**
     * @param xml        the file written by "pdftohtml -xml"
     * @param imageDir   the folder with the picture files that the XML refers to
     * @param pxPerPoint how many XML units make one point (pdftohtml -zoom 2 gives 2)
     */
    static Result convert(Path xml, Path imageDir, double pxPerPoint, Path outDocx) throws IOException {
        Parsed parsed = parse(xml);
        List<PageData> pages = parsed.pages();
        Map<Integer, FontSpec> fonts = parsed.fonts();
        if (pages.isEmpty()) {
            throw FileMasterException.badRequest("No pages were found in this PDF.");
        }
        double k = 1.0 / pxPerPoint;                  // XML units -> points

        // ----- lines of every page, then remove repeating headers, footers and page numbers -----
        List<List<Line>> allLines = new ArrayList<>();
        for (PageData page : pages) {
            allLines.add(buildLines(page));
        }
        removeRunningHeadersAndFooters(pages, allLines);

        // ----- document margins from the text itself -----
        List<Double> lefts = new ArrayList<>();
        List<Double> rights = new ArrayList<>();
        for (List<Line> lines : allLines) {
            if (lines.size() >= 3) {
                lefts.add(percentile(lines, true, 0.05));
                rights.add(percentile(lines, false, 0.95));
            }
        }
        PageData first = pages.get(0);
        double marginLeft = lefts.isEmpty() ? 72 * pxPerPoint : median(lefts);
        double textRight = rights.isEmpty() ? first.width - 72 * pxPerPoint : median(rights);
        double textWidth = Math.max(100 * pxPerPoint, textRight - marginLeft);
        // The Word page margins: the left one from the text, the right one is never larger than the left one. (The right
        // edge of the TEXT alone is a bad guide: on pages of short bullet lines it is far to the left.)
        double rightMargin = Math.min(marginLeft, Math.max(28 * pxPerPoint, first.width - textRight));
        double areaWidth = Math.max(100 * pxPerPoint, first.width - marginLeft - rightMargin);

        // ----- blocks -----
        List<DocxBuilder.Block> blocks = new ArrayList<>();
        int paragraphs = 0;
        int pictures = 0;
        int characters = 0;
        boolean breakBeforeNext = false;
        double previousPageEmptyShare = 0;

        for (int p = 0; p < pages.size(); p++) {
            PageData page = pages.get(p);
            List<Para> paras = buildParagraphs(allLines.get(p), marginLeft, textRight, textWidth, fonts, k);
            List<Pic> pics = usablePictures(page, !allLines.get(p).isEmpty(), pxPerPoint);

            // merge paragraphs and pictures in reading order (top to bottom)
            int pi = 0;
            int ci = 0;
            double previousBottom = -1;
            boolean firstOnPage = true;
            double pageBottom = 0;
            while (pi < paras.size() || ci < pics.size()) {
                boolean takePicture = ci < pics.size()
                        && (pi >= paras.size() || pics.get(ci).top() <= paras.get(pi).top() + 2 * pxPerPoint);
                boolean pageBreak = firstOnPage && (breakBeforeNext && !blocks.isEmpty());
                if (takePicture) {
                    Pic pic = pics.get(ci++);
                    DocxBuilder.PictureBlock block = pictureBlock(pic, imageDir, page, marginLeft, areaWidth, k,
                            previousBottom < 0 ? 0 : pic.top() - previousBottom, pageBreak, pxPerPoint);
                    if (block != null) {
                        blocks.add(block);
                        pictures++;
                        previousBottom = pic.top() + pic.height();
                        pageBottom = Math.max(pageBottom, previousBottom);
                        firstOnPage = false;
                    }
                } else {
                    Para para = paras.get(pi++);
                    DocxBuilder.ParaBlock block = paragraphBlock(para, marginLeft, areaWidth, first.width / 2, fonts, k,
                            previousBottom < 0 ? 0 : para.top() - previousBottom, pageBreak);
                    if (!block.runs().isEmpty()) {
                        blocks.add(block);
                        paragraphs++;
                        for (DocxBuilder.RunSpec run : block.runs()) {
                            characters += run.text().length();
                        }
                        previousBottom = para.bottom();
                        pageBottom = Math.max(pageBottom, previousBottom);
                        firstOnPage = false;
                    }
                }
            }
            // if the page ended early (a lot of empty space), the author probably wanted a new page
            previousPageEmptyShare = page.height > 0 ? (page.height - pageBottom) / page.height : 0;
            breakBeforeNext = !firstOnPage && previousPageEmptyShare > 0.22;
        }

        if (blocks.isEmpty()) {
            throw FileMasterException.badRequest("No text or pictures could be read from this PDF.");
        }

        int pageWidthTwips = (int) Math.round(first.width * k * 20);
        int pageHeightTwips = (int) Math.round(first.height * k * 20);
        int left = clamp((int) Math.round(marginLeft * k * 20), 560, 2400);
        int right = clamp((int) Math.round(rightMargin * k * 20), 560, 2400);
        DocxBuilder.write(outDocx, pageWidthTwips, pageHeightTwips, left, right, 1080, 1080, blocks);
        return new Result(pages.size(), paragraphs, pictures, characters);
    }

    // =====================================================================
    // reading the XML
    // =====================================================================

    private static Parsed parse(Path xml) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        List<PageData> pages = new ArrayList<>();
        Map<Integer, FontSpec> fonts = new HashMap<>();
        try (InputStream in = Files.newInputStream(xml)) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            PageData page = null;
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (r.getLocalName()) {
                    case "page" -> {
                        page = new PageData(num(r, "width"), num(r, "height"));
                        pages.add(page);
                    }
                    case "fontspec" -> fonts.put((int) num(r, "id"), new FontSpec(num(r, "size"),
                            attr(r, "family"), attr(r, "color")));
                    case "image" -> {
                        if (page != null && attr(r, "src") != null) {
                            page.pictures.add(new Pic(num(r, "top"), num(r, "left"), num(r, "width"), num(r, "height"),
                                    attr(r, "src")));
                        }
                    }
                    case "text" -> {
                        if (page != null) {
                            page.items.add(readText(r));
                        }
                    }
                    default -> { }
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            throw new IOException("Could not read the PDF layout", e);
        }
        return new Parsed(pages, fonts);
    }

    private static Item readText(XMLStreamReader r) throws XMLStreamException {
        Item item = new Item();
        item.top = num(r, "top");
        item.left = num(r, "left");
        item.width = num(r, "width");
        item.height = num(r, "height");
        item.font = (int) num(r, "font");
        int bold = 0;
        int italic = 0;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = r.getLocalName();
                if (name.equals("b")) {
                    bold++;
                } else if (name.equals("i")) {
                    italic++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = r.getLocalName();
                if (name.equals("text")) {
                    break;
                } else if (name.equals("b")) {
                    bold--;
                } else if (name.equals("i")) {
                    italic--;
                }
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                item.segs.add(new Seg(r.getText().replace('\u00A0', ' ').replace("\u200B", ""), bold > 0, italic > 0));
            }
        }
        return item;
    }

    private static String attr(XMLStreamReader r, String name) {
        return r.getAttributeValue(null, name);
    }

    private static double num(XMLStreamReader r, String name) {
        String v = attr(r, name);
        try {
            return v == null ? 0 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // =====================================================================
    // lines
    // =====================================================================

    private static List<Line> buildLines(PageData page) {
        List<Item> items = new ArrayList<>();
        for (Item item : page.items) {
            if (!item.text().isBlank()) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparingDouble(Item::bottom).thenComparingDouble(i -> i.left));

        List<Line> lines = new ArrayList<>();
        Line current = null;
        for (Item item : items) {
            if (current != null && Math.abs(item.bottom() - current.refBottom) <= 0.45 * Math.min(item.height, current.height)) {
                current.add(item);
            } else {
                current = new Line();
                current.add(item);
                current.refBottom = item.bottom();
                lines.add(current);
            }
        }
        for (Line line : lines) {
            line.items.sort(Comparator.comparingDouble(i -> i.left));
        }
        lines.sort(Comparator.comparingDouble(l -> l.top));
        return lines;
    }

    private static String plain(Line line) {
        StringBuilder sb = new StringBuilder();
        double previousRight = Double.NaN;
        for (Item item : line.items) {
            String text = item.text();
            if (!Double.isNaN(previousRight) && item.left - previousRight > 0.12 * line.height
                    && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ' && !text.startsWith(" ")) {
                sb.append(' ');
            }
            sb.append(text);
            previousRight = item.right();
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static void removeRunningHeadersAndFooters(List<PageData> pages, List<List<Line>> allLines) {
        int count = pages.size();
        Map<String, Integer> seen = new HashMap<>();
        for (int p = 0; p < count; p++) {
            for (Line line : allLines.get(p)) {
                if (inBand(line, pages.get(p))) {
                    seen.merge(key(plain(line)), 1, Integer::sum);
                }
            }
        }
        int threshold = Math.max(3, (int) Math.ceil(0.4 * count));
        for (int p = 0; p < count; p++) {
            PageData page = pages.get(p);
            allLines.get(p).removeIf(line -> {
                if (!inBand(line, page)) {
                    return false;
                }
                String text = plain(line);
                return PAGE_NUMBER.matcher(text).matches() || (count >= 3 && seen.getOrDefault(key(text), 0) >= threshold);
            });
        }
    }

    private static boolean inBand(Line line, PageData page) {
        return line.bottom < 0.07 * page.height || line.top > 0.93 * page.height;
    }

    private static String key(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").trim();
    }

    private static double percentile(List<Line> lines, boolean left, double fraction) {
        List<Double> values = new ArrayList<>();
        for (Line l : lines) {
            values.add(left ? l.left : l.right);
        }
        values.sort(null);
        int index = (int) Math.min(values.size() - 1, Math.max(0, Math.round(fraction * (values.size() - 1))));
        return values.get(index);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // =====================================================================
    // paragraphs
    // =====================================================================

    private static List<Para> buildParagraphs(List<Line> lines, double marginLeft, double textRight, double textWidth,
                                              Map<Integer, FontSpec> fonts, double k) {
        List<Para> paragraphs = new ArrayList<>();
        Para current = null;
        Line previous = null;
        for (Line line : lines) {
            if (current == null || startsNewParagraph(current, previous, line, textRight, textWidth, fonts, k)) {
                current = new Para();
                paragraphs.add(current);
                Matcher bullet = BULLET.matcher(plain(line));
                if (bullet.find()) {
                    current.bullet = true;
                    current.marker = bullet.group(1);
                    current.markerLeft = line.left;
                    Item firstItem = line.items.get(0);
                    current.textLeft = firstItem.text().trim().equals(current.marker) && line.items.size() > 1
                            ? line.items.get(1).left
                            : line.left + Math.max(0.9, 0.6 * current.marker.length() + 0.5) * line.height * 0.6;
                }
            }
            current.lines.add(line);
            previous = line;
        }
        return paragraphs;
    }

    private static boolean startsNewParagraph(Para current, Line previous, Line line, double textRight, double textWidth,
                                              Map<Integer, FontSpec> fonts, double k) {
        double h = line.height;
        double gap = line.top - previous.bottom;
        if (gap > 0.5 * h) {
            return true;
        }
        if (BULLET.matcher(plain(line)).find()) {
            return true;
        }
        // a continuation line of a bullet is indented to where the bullet's text starts
        boolean bulletContinuation = current.bullet && Math.abs(line.left - current.textLeft) < 0.8 * h;
        if (!bulletContinuation) {
            if (previous.right < textRight - 0.12 * textWidth) {
                return true;                              // the previous line ended early: that paragraph is over
            }
            if (line.left - previous.left > 1.2 * h && current.lines.size() > 1) {
                return true;                              // indented again
            }
        }
        if (Math.abs(dominantSize(line, fonts) - dominantSize(previous, fonts)) > 0.9 / k) {   // more than ~1 pt different
            return true;                                  // a different font size: heading or caption
        }
        return allBold(line) != allBold(previous);        // bold heading line next to normal text
    }

    private static double dominantSize(Line line, Map<Integer, FontSpec> fonts) {
        Item best = line.items.get(0);
        for (Item i : line.items) {
            if (i.text().length() > best.text().length()) {
                best = i;
            }
        }
        FontSpec spec = fonts.get(best.font);
        return spec == null ? best.height : spec.size();
    }

    private static boolean allBold(Line line) {
        boolean any = false;
        for (Item item : line.items) {
            for (Seg s : item.segs) {
                if (!s.text().isBlank()) {
                    any = true;
                    if (!s.bold()) {
                        return false;
                    }
                }
            }
        }
        return any;
    }

    // =====================================================================
    // blocks
    // =====================================================================

    private static DocxBuilder.ParaBlock paragraphBlock(Para para, double marginLeft, double textWidth, double pageCenter,
                                                        Map<Integer, FontSpec> fonts, double k, double gap,
                                                        boolean pageBreak) {
        Line firstLine = para.lines.get(0);
        double h = firstLine.height;

        // text runs
        RunBuilder runs = new RunBuilder();
        boolean firstLineOfParagraph = true;
        for (Line line : para.lines) {
            String skip = firstLineOfParagraph && para.bullet ? para.marker : "";
            if (!firstLineOfParagraph) {
                runs.separator(runs.endsWithHyphen() ? "" : " ");
            } else if (para.bullet) {
                runs.markerText(para.marker + "\t", firstItemStyle(line, fonts, k, skip));
            }
            appendLine(runs, line, fonts, k, skip);
            firstLineOfParagraph = false;
        }

        String align = "left";
        int leftTwips = 0;
        int hangingTwips = 0;
        double minLeft = para.minLeft();
        if (para.bullet) {
            leftTwips = (int) Math.round(Math.max(0, para.textLeft - marginLeft) * k * 20);
            hangingTwips = (int) Math.round(Math.max(12, Math.min(40, para.textLeft - para.markerLeft)) * k * 20);
            hangingTwips = Math.min(hangingTwips, leftTwips > 0 ? leftTwips : hangingTwips);
        } else {
            double centerOfText = marginLeft + textWidth / 2;
            boolean centered = minLeft - marginLeft > 0.06 * textWidth;
            for (Line l : para.lines) {
                double middle = (l.left + l.right) / 2;
                double off = Math.min(Math.abs(middle - centerOfText), Math.abs(middle - pageCenter));   // centred on the area or on the page
                if (off > 0.03 * textWidth) {
                    centered = false;
                }
            }
            if (centered) {
                align = "center";
            } else if (minLeft - marginLeft > 0.8 * h) {
                leftTwips = (int) Math.round((minLeft - marginLeft) * k * 20);
            }
        }
        int before = gap > 0.3 * h ? (int) Math.round(Math.min(36 / k, gap - 0.1 * h) * k * 20) : 0;
        return new DocxBuilder.ParaBlock(runs.build(), align, leftTwips, hangingTwips, Math.max(0, before), pageBreak);
    }

    private static void appendLine(RunBuilder runs, Line line, Map<Integer, FontSpec> fonts, double k, String skipMarker) {
        double previousRight = Double.NaN;
        boolean skipping = !skipMarker.isEmpty();
        for (Item item : line.items) {
            FontSpec spec = fonts.get(item.font);
            double size = spec == null ? item.height * k * 0.8 : spec.size() * k;
            String family = cleanFamily(spec == null ? null : spec.family());
            String color = spec == null || spec.color() == null ? null : spec.color().replace("#", "");

            for (Seg seg : item.segs) {
                String text = seg.text();
                if (skipping) {
                    String stripped = text.stripLeading();
                    if (stripped.isEmpty()) {
                        continue;
                    }
                    if (stripped.startsWith(skipMarker)) {
                        text = stripped.substring(skipMarker.length());
                    } else if (stripped.equals(skipMarker.trim())) {
                        text = "";
                    }
                    skipping = false;
                    previousRight = Double.NaN;
                    if (text.isBlank()) {
                        continue;
                    }
                }
                if (!Double.isNaN(previousRight)) {
                    double gap = item.left - previousRight;
                    if (gap > 3.0 * line.height) {
                        runs.separator("\t");
                    } else if (gap > 0.12 * line.height) {
                        runs.separator(" ");
                    }
                    previousRight = Double.NaN;
                }
                runs.append(text, new DocxBuilder.RunSpec("", seg.bold(), seg.italic(), size, family, color));
            }
            previousRight = item.right();
        }
    }

    private static DocxBuilder.RunSpec firstItemStyle(Line line, Map<Integer, FontSpec> fonts, double k, String skip) {
        Item chosen = line.items.size() > 1 && line.items.get(0).text().trim().equals(skip.trim()) ? line.items.get(1) : line.items.get(0);
        FontSpec spec = fonts.get(chosen.font);
        double size = spec == null ? chosen.height * k * 0.8 : spec.size() * k;
        String color = spec == null || spec.color() == null ? null : spec.color().replace("#", "");
        return new DocxBuilder.RunSpec("", false, false, size, cleanFamily(spec == null ? null : spec.family()), color);
    }

    private static String cleanFamily(String family) {
        if (family == null) {
            return null;
        }
        String name = SUBSET_PREFIX.matcher(family).replaceFirst("");
        return STYLE_SUFFIX.matcher(name).replaceFirst("");
    }

    // ----- pictures -----

    private static List<Pic> usablePictures(PageData page, boolean pageHasText, double pxPerPoint) {
        List<Pic> usable = new ArrayList<>();
        for (Pic pic : page.pictures) {
            boolean tiny = pic.width() < 12 * pxPerPoint || pic.height() < 12 * pxPerPoint;
            boolean wholePage = pic.width() > 0.9 * page.width && pic.height() > 0.9 * page.height;
            if (tiny || (wholePage && pageHasText)) {
                continue;                                     // decorations and page backgrounds
            }
            usable.add(pic);
        }
        usable.sort(Comparator.comparingDouble(Pic::top));
        return usable;
    }

    private static DocxBuilder.PictureBlock pictureBlock(Pic pic, Path imageDir, PageData page, double marginLeft,
                                                         double textWidth, double k, double gap, boolean pageBreak,
                                                         double pxPerPoint) {
        Path base = imageDir.toAbsolutePath().normalize();
        Path file = base.resolve(pic.src()).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            return null;
        }
        double width = pic.width();
        double height = pic.height();
        double maxHeight = page.height - 2 * 54 * pxPerPoint;
        double scale = Math.min(1.0, Math.min(textWidth / width, maxHeight / height));
        width *= scale;
        height *= scale;

        String align = "left";
        int leftTwips = 0;
        double center = pic.left() + pic.width() / 2;
        if (Math.abs(center - page.width / 2) <= 0.05 * page.width) {
            align = "center";
        } else if (pic.left() - marginLeft > 0 && pic.left() - marginLeft + width <= textWidth) {
            leftTwips = (int) Math.round((pic.left() - marginLeft) * k * 20);
        }
        int before = gap > 4 * pxPerPoint ? (int) Math.round(Math.min(24 / k, gap * 0.8) * k * 20) : 0;
        return new DocxBuilder.PictureBlock(file, width * k, height * k, align, leftTwips, Math.max(0, before), pageBreak);
    }

    // =====================================================================
    // building runs without duplicate spaces
    // =====================================================================

    private static final class RunBuilder {
        private final List<DocxBuilder.RunSpec> runs = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private DocxBuilder.RunSpec style;
        private char last = ' ';
        private boolean empty = true;

        void markerText(String marker, DocxBuilder.RunSpec markerStyle) {
            flush();
            style = markerStyle;
            text.append(marker);
            last = '\t';
            empty = false;
            flush();
        }

        void separator(String s) {
            if (s.isEmpty() || empty) {
                return;
            }
            if (s.equals(" ") && (last == ' ' || last == '\t')) {
                return;
            }
            text.append(s);
            last = s.charAt(s.length() - 1);
        }

        void append(String piece, DocxBuilder.RunSpec pieceStyle) {
            String cleaned = piece.replaceAll("\\s+", " ");
            if (empty || last == ' ' || last == '\t') {
                cleaned = cleaned.stripLeading();
            }
            if (cleaned.isEmpty()) {
                return;
            }
            if (style != null && !sameStyle(style, pieceStyle)) {
                flush();
            }
            style = pieceStyle;
            text.append(cleaned);
            last = cleaned.charAt(cleaned.length() - 1);
            empty = false;
        }

        boolean endsWithHyphen() {
            return !empty && last == '-';
        }

        private void flush() {
            if (text.length() > 0 && style != null) {
                runs.add(new DocxBuilder.RunSpec(text.toString(), style.bold(), style.italic(), style.sizePt(),
                        style.font(), style.color()));
            }
            text.setLength(0);
        }

        List<DocxBuilder.RunSpec> build() {
            flush();
            // drop spaces at the very end
            if (!runs.isEmpty()) {
                DocxBuilder.RunSpec lastRun = runs.get(runs.size() - 1);
                String trimmed = lastRun.text().stripTrailing();
                if (trimmed.isEmpty()) {
                    runs.remove(runs.size() - 1);
                } else if (!trimmed.equals(lastRun.text())) {
                    runs.set(runs.size() - 1, new DocxBuilder.RunSpec(trimmed, lastRun.bold(), lastRun.italic(),
                            lastRun.sizePt(), lastRun.font(), lastRun.color()));
                }
            }
            return runs;
        }

        private static boolean sameStyle(DocxBuilder.RunSpec a, DocxBuilder.RunSpec b) {
            return a.bold() == b.bold() && a.italic() == b.italic() && Math.abs(a.sizePt() - b.sizePt()) < 0.3
                    && java.util.Objects.equals(a.font(), b.font()) && java.util.Objects.equals(a.color(), b.color());
        }
    }
}
