package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class LegacyBlurredImageTest {
    private static BufferedImage image(int w, int h, int argb) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    @Test
    void shrinkAveragesEverySourcePixel() {
        BufferedImage source = image(4, 4, 0xFF000000);
        // Left half white: after halving, the left column is white and the right one black.
        for (int y = 0; y < 4; y++) {
            source.setRGB(0, y, 0xFFFFFFFF);
            source.setRGB(1, y, 0xFFFFFFFF);
        }
        var shrunk = LegacyBlurredImage.shrink(source, 0.5F);
        assertEquals(2, shrunk.width());
        assertEquals(2, shrunk.height());
        assertEquals(0xFFFFFFFF, shrunk.argb()[0]);
        assertEquals(0xFF000000, shrunk.argb()[1]);
        assertEquals(0xFFFFFFFF, shrunk.argb()[2]);
        assertEquals(0xFF000000, shrunk.argb()[3]);
    }

    @Test
    void shrinkAveragesRatherThanSkipping() {
        BufferedImage source = image(2, 1, 0xFF000000);
        source.setRGB(0, 0, 0xFFFFFFFF);
        var shrunk = LegacyBlurredImage.shrink(source, 0.5F);
        int grey = shrunk.argb()[0] & 0xFF;
        assertTrue(Math.abs(grey - 127) <= 1, "expected the mean of white and black, got " + grey);
    }

    @Test
    void blurKeepsAFlatColourFlat() {
        var flat = LegacyBlurredImage.shrink(image(16, 16, 0xFF336699), 1.0F);
        var blurred = LegacyBlurredImage.blur(flat, 4, 1.0F);
        for (int argb : blurred.argb()) {
            assertEquals(0xFF336699, argb);
        }
    }

    @Test
    void blurSpreadsAPointSymmetricallyAndKeepsItsWeight() {
        BufferedImage source = image(21, 21, 0xFF000000);
        source.setRGB(10, 10, 0xFFFFFFFF);
        var blurred = LegacyBlurredImage.blur(LegacyBlurredImage.shrink(source, 1.0F), 6, 1.0F);
        int centre = blurred.argb()[10 * 21 + 10] & 0xFF;
        assertTrue(centre < 255 && centre > 0, "the point should be softened, was " + centre);
        assertEquals(blurred.argb()[10 * 21 + 8] & 0xFF, blurred.argb()[10 * 21 + 12] & 0xFF, 1);
        assertEquals(blurred.argb()[8 * 21 + 10] & 0xFF, blurred.argb()[12 * 21 + 10] & 0xFF, 1);
        assertTrue((blurred.argb()[10 * 21 + 8] & 0xFF) > 0, "energy should reach the neighbours");
    }

    @Test
    void saturationBoostDoesNotTouchGrey() {
        var grey = LegacyBlurredImage.blur(LegacyBlurredImage.shrink(image(8, 8, 0xFF808080), 1.0F), 2, 1.1F);
        assertEquals(0xFF808080, grey.argb()[0]);
    }
}
