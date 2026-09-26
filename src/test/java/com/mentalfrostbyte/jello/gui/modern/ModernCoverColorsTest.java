package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Cover colours, worked out on the CPU from encoded bytes (no GPU involved). */
class ModernCoverColorsTest {
    private static byte[] png(Color background, Color patch) throws Exception {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, 256, 256);
        g.setColor(patch);
        g.fillRect(40, 40, 140, 140);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void theMostVividHueBecomesTheAccentAndADarkBackground() throws Exception {
        ModernCoverColors.Palette palette = ModernCoverColors.extract(png(new Color(20, 20, 24), new Color(220, 40, 60)));
        assertNotNull(palette);
        float[] accent = Color.RGBtoHSB(palette.accent() >> 16 & 0xFF, palette.accent() >> 8 & 0xFF, palette.accent() & 0xFF, null);
        float[] deep = Color.RGBtoHSB(palette.deep() >> 16 & 0xFF, palette.deep() >> 8 & 0xFF, palette.deep() & 0xFF, null);
        assertTrue(accent[0] > 0.9F || accent[0] < 0.05F, "a red accent, hue " + accent[0]);
        assertTrue(accent[2] > 0.85F, "bright");
        assertTrue(deep[2] < 0.3F, "dark enough for white text: " + deep[2]);
    }

    @Test
    void aGreyCoverHasNoPalette() throws Exception {
        assertNull(ModernCoverColors.extract(png(new Color(30, 30, 30), new Color(200, 200, 200))));
    }

    @Test
    void unreadableBytesHaveNoPalette() {
        assertNull(ModernCoverColors.extract(new byte[]{1, 2, 3, 4}));
    }
}
