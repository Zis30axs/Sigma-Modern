package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.gui.modern.ModernBlockFlyCounter.BorderSample;
import com.mentalfrostbyte.jello.gui.modern.ModernBlockFlyCounter.Contour;
import com.mentalfrostbyte.jello.gui.modern.ModernBlockFlyCounter.Palette;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

class ModernBlockFlyCounterTest {
    private static BufferedImage solidBlock(final int w, final int h, final Color base, final Color accent) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(base);
        g.fillRect(0, 0, w, h);
        if (accent != null) {
            g.setColor(accent);
            g.fillRect(w / 4, h / 4, w / 2, h / 2);
        }
        g.dispose();
        return image;
    }

    @Test
    void sampleImageExtractsVividAndAchromaticBlockColors() {
        Integer crimson = ModernBlockFlyCounter.sampleImage(
                solidBlock(16, 16, new Color(175, 32, 46), new Color(215, 42, 62)), 0xFFFFFF);
        assertNotNull(crimson);
        assertTrue(((crimson >> 16) & 0xFF) > 165);
        assertTrue(((crimson >> 8) & 0xFF) < 70);

        Integer oak = ModernBlockFlyCounter.sampleImage(
                solidBlock(16, 16, new Color(164, 131, 79), new Color(184, 152, 94)), 0xFFFFFF);
        assertNotNull(oak);
        int oakR = (oak >> 16) & 0xFF, oakG = (oak >> 8) & 0xFF, oakB = oak & 0xFF;
        assertTrue(oakR > oakG && oakG > oakB);

        // Greyscale leaves multiplied by a biome tint turn vivid foliage green.
        Integer leaves = ModernBlockFlyCounter.sampleImage(
                solidBlock(16, 16, new Color(205, 205, 205), null), 0x48B518);
        assertNotNull(leaves);
        int leafR = (leaves >> 16) & 0xFF, leafG = (leaves >> 8) & 0xFF, leafB = leaves & 0xFF;
        assertTrue(leafG > leafR && leafG > leafB);

        Integer whiteWool = ModernBlockFlyCounter.sampleImage(
                solidBlock(16, 16, new Color(242, 244, 247), null), 0xFFFFFF);
        assertNotNull(whiteWool);
        assertTrue(((whiteWool >> 16) & 0xFF) > 230 && (whiteWool & 0xFF) > 230);

        Integer obsidian = ModernBlockFlyCounter.sampleImage(
                solidBlock(16, 16, new Color(22, 16, 34), null), 0xFFFFFF);
        assertNotNull(obsidian);
        assertTrue(((obsidian >> 16) & 0xFF) < 50 && (obsidian & 0xFF) < 60);

        // Animated vertical texture strip: frame 0 [0..15] is green; frames 1..3 [16..63] are red.
        BufferedImage strip = new BufferedImage(16, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = strip.createGraphics();
        g.setColor(new Color(40, 210, 90));
        g.fillRect(0, 0, 16, 16);
        g.setColor(new Color(235, 30, 30));
        g.fillRect(0, 16, 16, 48);
        g.dispose();
        Integer frame0 = ModernBlockFlyCounter.sampleImage(strip, 0xFFFFFF);
        assertNotNull(frame0);
        assertTrue(((frame0 >> 8) & 0xFF) > ((frame0 >> 16) & 0xFF), "only frame 0 should be sampled");
    }

    @Test
    void paletteKeepsGlassTranslucentAndScrollingLineVisibleForAllBlocks() {
        int[] samples = {0xD9383A, 0x6AB04C, 0x4FE3EA, 0xFAFAFA, 0x7E7E7E, 0x101014};
        for (int rgb : samples) {
            Palette p = ModernBlockFlyCounter.paletteFromRgb(rgb);
            int glassAlpha = p.glassTintArgb() >>> 24;
            assertTrue(glassAlpha >= 0x40 && glassAlpha <= 0x80,
                    "glass alpha should remain translucent for #" + Integer.toHexString(rgb));

            float[] lineHsb = Color.RGBtoHSB(
                    (p.scrollLineRgb() >> 16) & 0xFF,
                    (p.scrollLineRgb() >> 8) & 0xFF,
                    p.scrollLineRgb() & 0xFF, null);
            assertTrue(lineHsb[2] >= 0.55F,
                    "scrolling border line brightness must stay visible for #" + Integer.toHexString(rgb));

            float[] inputHsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
            if (inputHsb[1] >= 0.20F) {
                float dHue = Math.abs(lineHsb[0] - inputHsb[0]);
                dHue = Math.min(dHue, 1.0F - dHue);
                assertTrue(dHue < 0.08F, "hue should preserve block identity for #" + Integer.toHexString(rgb));
            }
        }
    }

    @Test
    void contourAndScrollingBorderQuadsHaveValidWindingAndScrollingMotion() {
        Contour[] contours = {
            Contour.build(100.0F, 80.0F, 86.0F, 26.0F, 13.0F),
            Contour.build(50.0F, 40.0F, 110.0F, 32.0F, 8.0F)
        };
        Palette palette = ModernBlockFlyCounter.paletteFromRgb(0x45C4F9);

        for (Contour c : contours) {
            assertTrue(c.count() >= 20);
            assertEquals(0.0F, c.u()[0], 1.0E-5F);
            assertEquals(1.0F, c.u()[c.count()], 1.0E-5F);
            for (int i = 0; i < c.count(); i++) {
                assertTrue(Float.isFinite(c.px()[i]) && Float.isFinite(c.py()[i]));
                double len = Math.hypot(c.nx()[i], c.ny()[i]);
                assertEquals(1.0, len, 1.0E-4, "contour normals must be unit length");
                assertTrue(c.u()[i + 1] > c.u()[i], "cumulative arc length must increase monotonically");
            }

            Matrix3x2f pose = new Matrix3x2f().translate(12.0F, -4.0F).scale(1.25F);
            AtomicInteger quads = new AtomicInteger();
            ModernBlockFlyCounter.emitVertices(c, pose, 0.25F, palette, 1.0F, 2,
                (x0, y0, c0, x1, y1, c1, x2, y2, c2, x3, y3, c3) -> {
                    assertTrue(Float.isFinite(x0) && Float.isFinite(y0));
                    assertTrue(Float.isFinite(x1) && Float.isFinite(y1));
                    assertTrue(Float.isFinite(x2) && Float.isFinite(y2));
                    assertTrue(Float.isFinite(x3) && Float.isFinite(y3));
                    float cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
                    assertTrue(cross < -1.0E-4F,
                            "quad winding must stay negative like ColoredRectangleRenderState: " + cross);
                    quads.incrementAndGet();
                });
            assertTrue(quads.get() > c.count() * 3);
        }

        // Peak brightness in the first half-perimeter shifts forward with phase.
        float peak0 = peakPositionInFirstHalf(0.05F, palette);
        float peak1 = peakPositionInFirstHalf(0.20F, palette);
        float peak2 = peakPositionInFirstHalf(0.35F, palette);
        assertTrue(peak1 > peak0 && peak2 > peak1,
                "scrolling peak should move smoothly clockwise with phase: " + peak0 + " -> " + peak1 + " -> " + peak2);
    }

    private static float peakPositionInFirstHalf(final float phase, final Palette palette) {
        int bestStep = -1;
        int bestAlpha = -1;
        for (int step = 0; step < 500; step++) {
            float u = step / 1000.0F;
            BorderSample sample = ModernBlockFlyCounter.sampleBorder(u, phase, palette, 1.0F);
            int alpha = sample.coreArgb() >>> 24;
            if (alpha > bestAlpha) {
                bestAlpha = alpha;
                bestStep = step;
            }
        }
        return bestStep / 1000.0F;
    }
}
