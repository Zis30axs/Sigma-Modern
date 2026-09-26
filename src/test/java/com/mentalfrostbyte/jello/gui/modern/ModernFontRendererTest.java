package com.mentalfrostbyte.jello.gui.modern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

class ModernFontRendererTest {
    private final ModernFontRenderer renderer = new ModernFontRenderer();

    @AfterEach void closeNativeObjects() { renderer.close(); }

    @Test
    void glyphEdgesRetainFractionalCoverageAndTransparentPadding() {
        var image = renderer.rasterize("Sigma / 中文", 4).image();
        int partial = 0, opaque = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha > 0 && alpha < 255) partial++;
                if (alpha == 255) opaque++;
                if (x == 0 || y == 0 || x == image.getWidth() - 1 || y == image.getHeight() - 1) assertEquals(0, alpha);
            }
        }
        assertTrue(partial > 100, "Edges must contain coverage values, not binary pixel masks");
        assertTrue(opaque > 100, "Glyph interiors must remain solid");
    }

    @Test
    void largeTitlesReceiveNewPixelsInsteadOfMagnifiedSmallGlyphs() {
        var small = renderer.rasterize("SIGMA", 4);
        var large = renderer.rasterize("SIGMA", 20);
        assertEquals(small.image().getWidth() * 5, large.image().getWidth());
        assertEquals(small.image().getHeight() * 5, large.image().getHeight());
        assertEquals(small.left(), large.left());
        assertEquals(small.top(), large.top());
    }

    @Test
    void clippingHonorsMeasuredWidthAndUnicodeBoundaries() {
        String input = "A\u0301\uD83D\uDE00中文 Sigma";
        for (int width = 0; width <= renderer.width(input); width++) {
            String fitted = renderer.fit(input, width);
            assertTrue(input.startsWith(fitted));
            assertTrue(renderer.width(fitted) <= width);
            assertFalse(fitted.endsWith("A"), "Do not separate the combining accent");
            assertFalse(!fitted.isEmpty() && Character.isHighSurrogate(fitted.charAt(fitted.length() - 1)));
        }
        assertEquals(input, renderer.fit(input, renderer.width(input)));
        assertEquals(0, renderer.width(""));
        assertEquals("", renderer.fit(input, -1));
    }

    @Test
    void longRunsHaveBoundedTextureDimensions() {
        var raster = renderer.rasterize("Modern ".repeat(600), 32);
        assertTrue(raster.image().getWidth() <= 4096);
        assertTrue(raster.image().getHeight() <= 2048);
    }

    @Test
    void roundedMasksHaveSmoothCornersAndSolidStretchRegions() {
        var image = ModernShapeRenderer.mask(9, 4);
        assertEquals(0, image.getRGB(0, 0) >>> 24);
        assertEquals(255, image.getRGB(image.getWidth() / 2, image.getHeight() / 2) >>> 24);
        int partial = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int alpha = image.getRGB(x, y) >>> 24;
            if (alpha > 0 && alpha < 255) partial++;
        }
        assertTrue(partial > 50, "Round corners must carry fractional edge coverage");
        for (int y = 4; y < image.getHeight() - 4; y++) {
            assertEquals(255, image.getRGB(image.getWidth() / 2, y) >>> 24, "Nine-slice seams must remain opaque");
        }
    }

    @Test
    void chineseFallbackProducesDifferentGlyphsInsteadOfRepeatedMissingBoxes() {
        var first = renderer.rasterize("中", 4).image();
        var second = renderer.rasterize("文", 4).image();
        assertFalse(java.util.Arrays.equals(first.rgba(), second.rgba()));
    }

    /** The chat lays text out by summed advances and draws it as shaped runs; the two must agree. */
    @Test
    void plainShapingRunsAreAsWideAsTheirSummedAdvances() {
        String[] samples = {"AVATAR To WAVE", "office fifty ffi", "Sigma chat: [Server] Hello, world!", "中文测试 mixed 混排 text", "Tj yj Yo Va"};
        try (var plain = new ModernFontRenderer(ModernFontRenderer.SERIF_TEXT, false, false, ModernFontRenderer.PLAIN);
             var bold = new ModernFontRenderer(ModernFontRenderer.SERIF_TEXT, true, false, ModernFontRenderer.PLAIN);
             var italic = new ModernFontRenderer(ModernFontRenderer.SERIF_TEXT, false, true, ModernFontRenderer.PLAIN)) {
            for (String sample : samples) {
                float summed = (float)sample.codePoints().mapToDouble(plain::advance).sum();
                // Drawn as ModernChat splits it: a new run wherever the face hands over to a system font.
                float drawn = 0F;
                StringBuilder run = new StringBuilder();
                Boolean covered = null;
                for (int codepoint : sample.codePoints().toArray()) {
                    if (covered != null && covered != plain.covers(codepoint)) {
                        drawn += plain.measure(run.toString());
                        run.setLength(0);
                    }
                    covered = plain.covers(codepoint);
                    run.appendCodePoint(codepoint);
                }
                drawn += plain.measure(run.toString());
                assertEquals(summed, drawn, 0.01F * sample.length(), sample);
                // Synthesized bold and italic keep the regular advances, so one metrics font serves all four.
                assertEquals(plain.measure(sample), bold.measure(sample), 0.01F * sample.length(), "bold " + sample);
                assertEquals(plain.measure(sample), italic.measure(sample), 0.01F * sample.length(), "italic " + sample);
            }
        }
    }
}
