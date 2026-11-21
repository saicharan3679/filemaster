package com.filemaster.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A picture and its transparency mask must be put back together (otherwise transparent areas turn black). */
class PdfImageMasksTest {

    @TempDir
    Path dir;

    private static final String LISTING = """
            page   num  type   width height color comp bpc  enc interp  object ID x-ppi y-ppi size ratio
            --------------------------------------------------------------------------------------------
               1     0 image    872   472  rgb     3   8  jpeg   no       112  0   148   148  61K 3.2%
               1     1 smask    872   472  gray    1   8  image  no       113  0   148   148  451B 0.1%
               2     2 image   1536   434  rgb     3   8  jpeg   no       133  0   245   200  61K 3.2%
            """;

    @Test
    void findsThePagesThatHaveMasks() {
        assertTrue(PdfImageMasks.hasSoftMasks(LISTING));
        assertEquals(List.of(1), PdfImageMasks.pagesWithSoftMasks(LISTING));
    }

    @Test
    void groupsPagesIntoRanges() {
        var ranges = PopplerService.ranges(List.of(1, 2, 3, 7, 8, 20));
        assertEquals(3, ranges.size());
        assertEquals(1, ranges.get(0)[0]);
        assertEquals(3, ranges.get(0)[1]);
        assertEquals(20, ranges.get(2)[0]);
    }

    @Test
    void combinesPictureAndMaskIntoATransparentPng() throws IOException {
        BufferedImage picture = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        BufferedImage mask = new BufferedImage(4, 2, BufferedImage.TYPE_BYTE_GRAY);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 2; y++) {
                picture.setRGB(x, y, 0x336699);
                mask.getRaster().setSample(x, y, 0, x < 2 ? 0 : 255);     // left half transparent, right half visible
            }
        }
        ImageIO.write(picture, "png", dir.resolve("picture.png").toFile());
        ImageIO.write(mask, "png", dir.resolve("mask.png").toFile());

        assertTrue(PdfImageMasks.compose(dir.resolve("picture.png"), dir.resolve("mask.png"), dir.resolve("out.png")));

        BufferedImage result = ImageIO.read(dir.resolve("out.png").toFile());
        assertEquals(0, (result.getRGB(0, 0) >>> 24) & 0xFF, "masked area is transparent");
        assertEquals(255, (result.getRGB(3, 0) >>> 24) & 0xFF, "visible area is opaque");
        assertEquals(0x336699, result.getRGB(3, 0) & 0xFFFFFF, "colours are kept");
    }
}
