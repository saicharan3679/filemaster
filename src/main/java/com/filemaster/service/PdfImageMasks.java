package com.filemaster.service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Some pictures in a PDF come with a separate transparency mask ("soft mask"). "pdftohtml" extracts the picture
 * but drops that mask, so transparent areas turn black. This class puts the two back together:
 *
 *  1. "pdfimages -list" tells which pictures have a mask,
 *  2. "pdfimages -png -p" has written the masks as separate files,
 *  3. each masked picture is rewritten as a PNG with real transparency,
 *  4. the layout XML is updated to point to the new file.
 */
final class PdfImageMasks {

    private record Entry(int page, int num, String type, int width, int height) {
    }

    private static final Pattern MASK_FILE = Pattern.compile("^img-(\\d+)-(\\d+)\\.png$");
    private static final Pattern PICTURE_TAG = Pattern.compile("<image\\b[^>]*\\bsrc=\"([^\"]+)\"[^>]*>");
    private static final Pattern PAGE_TAG = Pattern.compile("<page\\b[^>]*\\bnumber=\"(\\d+)\"");

    private PdfImageMasks() {
    }

    /** Parses the table printed by "pdfimages -list". */
    static List<Entry> parseList(String listing) {
        List<Entry> entries = new ArrayList<>();
        for (String line : listing.split("\\R")) {
            String[] c = line.trim().split("\\s+");
            if (c.length >= 5 && c[0].matches("\\d+") && c[1].matches("\\d+")
                    && (c[2].equals("image") || c[2].equals("smask") || c[2].equals("mask") || c[2].equals("stencil"))) {
                entries.add(new Entry(Integer.parseInt(c[0]), Integer.parseInt(c[1]), c[2],
                        Integer.parseInt(c[3]), Integer.parseInt(c[4])));
            }
        }
        return entries;
    }

    /** The page numbers (ascending) that contain a soft mask. */
    static List<Integer> pagesWithSoftMasks(String listing) {
        return parseList(listing).stream().filter(e -> e.type().equals("smask")).map(Entry::page).distinct().sorted().toList();
    }

    /** True when the listing contains at least one soft mask. */
    static boolean hasSoftMasks(String listing) {
        return parseList(listing).stream().anyMatch(e -> e.type().equals("smask"));
    }

    /**
     * Rewrites masked pictures. @param folder the folder with out.xml, the pictures and the mask files (img-PPP-NNN.png)
     * @return how many pictures got their transparency back
     */
    static int apply(Path xmlFile, Path folder, String listing) throws IOException {
        folder = folder.toAbsolutePath().normalize();
        List<Entry> entries = parseList(listing);
        Map<Integer, Path> maskFiles = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "img-*.png")) {
            for (Path file : files) {
                Matcher m = MASK_FILE.matcher(file.getFileName().toString());
                if (m.matches()) {
                    maskFiles.put(Integer.parseInt(m.group(2)), file);
                }
            }
        }
        if (maskFiles.isEmpty()) {
            return 0;
        }

        // for every picture entry: the soft mask that directly follows it (same size), if any
        Map<Entry, Entry> maskOf = new HashMap<>();
        for (int i = 0; i + 1 < entries.size(); i++) {
            Entry a = entries.get(i);
            Entry b = entries.get(i + 1);
            if (a.type().equals("image") && b.type().equals("smask") && a.page() == b.page()
                    && a.width() == b.width() && a.height() == b.height()) {
                maskOf.put(a, b);
            }
        }

        String xml = Files.readString(xmlFile, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();
        Matcher pageMatcher = PAGE_TAG.matcher(xml);
        List<int[]> pages = new ArrayList<>();   // {start index, page number}
        while (pageMatcher.find()) {
            pages.add(new int[]{pageMatcher.start(), Integer.parseInt(pageMatcher.group(1))});
        }

        int fixed = 0;
        int cursor = 0;
        Map<Integer, List<Entry>> consumed = new HashMap<>();
        Matcher pictures = PICTURE_TAG.matcher(xml);
        while (pictures.find()) {
            int page = pageAt(pages, pictures.start());
            String src = pictures.group(1);
            Path file = folder.resolve(src).normalize();   // folder is absolute and normalized
            if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
                continue;
            }
            int[] size = pixelSize(file);
            if (size == null) {
                continue;
            }
            // the first not-yet-used picture of this page with the same pixel size
            List<Entry> used = consumed.computeIfAbsent(page, k -> new ArrayList<>());
            Entry match = null;
            for (Entry e : entries) {
                if (e.page() == page && e.type().equals("image") && e.width() == size[0] && e.height() == size[1]
                        && !used.contains(e)) {
                    match = e;
                    break;
                }
            }
            if (match == null) {
                continue;
            }
            used.add(match);
            Entry mask = maskOf.get(match);
            if (mask == null || !maskFiles.containsKey(mask.num())) {
                continue;
            }
            Path composed = folder.resolve(stripExtension(src) + ".masked.png");
            if (compose(file, maskFiles.get(mask.num()), composed)) {
                result.append(xml, cursor, pictures.start(1));   // up to the start of group 1 (the src value)
                result.append(composed.getFileName());
                cursor = pictures.end(1);
                fixed++;
            }
        }
        result.append(xml, cursor, xml.length());
        if (fixed > 0) {
            Files.writeString(xmlFile, result.toString(), StandardCharsets.UTF_8);
        }
        return fixed;
    }

    private static int pageAt(List<int[]> pages, int position) {
        int page = 1;
        for (int[] p : pages) {
            if (p[0] <= position) {
                page = p[1];
            }
        }
        return page;
    }

    /** Picture + gray mask -> PNG with transparency. Returns false if the files cannot be read. */
    static boolean compose(Path picture, Path mask, Path out) throws IOException {
        BufferedImage base = ImageIO.read(picture.toFile());
        BufferedImage alpha = ImageIO.read(mask.toFile());
        if (base == null || alpha == null) {
            return false;
        }
        int w = base.getWidth();
        int h = base.getHeight();
        if (alpha.getWidth() != w || alpha.getHeight() != h) {
            return false;
        }
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = base.getRGB(x, y) & 0x00FFFFFF;
                int a = alpha.getRaster().getSample(x, y, 0) & 0xFF;
                result.setRGB(x, y, (a << 24) | rgb);
            }
        }
        return ImageIO.write(result, "png", out.toFile());
    }

    private static int[] pixelSize(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new File(file.toString()))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
