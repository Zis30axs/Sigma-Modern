package com.mentalfrostbyte.jello.gui.modern;

import io.github.humbleui.skija.FontMetrics;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The old client's typefaces, for the Jello and Classic presentations.
 *
 * <p>Jello set its text in Helvetica Neue Light (titles, labels) and Medium (headings); Classic used a plain
 * grotesque. They go through the same Skia path as SigmaModern's serif ({@link ModernFontRenderer}), so the
 * text is anti-aliased, rasterized at the device's real density and cached, but sized the way the old screens
 * were: {@code size} is the old AWT point size, which is the em height in framebuffer pixels. Callers draw
 * inside a {@code LegacyCanvas}, whose unit is one framebuffer pixel, so a {@code JelloLightFont20} is
 * {@code draw(g, Face.JELLO_LIGHT, text, x, y, 20, color)}.</p>
 *
 * <p>The origin is the top of the face's ascent, as it was with the AWT fonts, so an old {@code (x, y)} keeps
 * meaning the top-left of the text.</p>
 */
public final class LegacyFonts {
    public enum Face {
        JELLO_LIGHT("/assets/minecraft/font/sigma/helvetica_neue_light.ttf"),
        JELLO_MEDIUM("/assets/minecraft/font/sigma/helvetica_neue_medium.ttf"),
        CLASSIC("/assets/minecraft/font/sigma/regular.ttf");

        private final String resource;
        private ModernFontRenderer renderer;

        Face(String resource) {
            this.resource = resource;
        }

        private ModernFontRenderer renderer() {
            if (this.renderer == null) this.renderer = ModernFontRenderer.legacy(this.resource);
            return this.renderer;
        }
    }

    private LegacyFonts() {}

    /** Draws {@code text} with its top-left at {@code (x, y)} in the current pose's units. */
    public static void draw(GuiGraphicsExtractor g, Face face, String text, float x, float y, float size, int color) {
        if (text == null || text.isEmpty() || (color >>> 24) == 0) return;
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, y);
            float scale = size / ModernFontRenderer.SIZE;
            g.pose().scale(scale, scale);
            face.renderer().draw(g, text, 0, 0, color, false);
        } finally {
            g.pose().popMatrix();
        }
    }

    /** The shaped width of {@code text} at {@code size}, in the same units as {@link #draw}. */
    public static float width(Face face, String text, float size) {
        if (text == null || text.isEmpty()) return 0;
        return face.renderer().measure(text) * size / ModernFontRenderer.SIZE;
    }

    /** The height of a line: the face's ascent plus descent at {@code size}. */
    public static float height(Face face, float size) {
        FontMetrics metrics = face.renderer().metrics();
        return (metrics.getDescent() - metrics.getAscent()) * size / ModernFontRenderer.SIZE;
    }
}
