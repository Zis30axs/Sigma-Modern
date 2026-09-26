package com.mentalfrostbyte.jello.gui.modern;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing pieces shared by SigmaModern's full-screen pages (worlds, servers, settings): the header with
 * its accent eyebrow and display-serif title, dark glass cards, chips, field pills and small-caps labels.
 */
final class ModernPage {
    /** Top of page content: clear of the window caption strip ({@link ModernWindowFrame#CAPTION_H}). */
    static final int TOP = ModernWindowFrame.CAPTION_H + 8;
    static final int CARD_SURFACE = 0xC40A1B29;

    private ModernPage() {}

    static int margin(int width) {
        return Math.max(12, Math.min(28, width / 24));
    }

    /** Eyebrow (glowing tick + wide-tracked caps) over a display-serif title, starting at ({@code x}, {@code y}). */
    static void header(GuiGraphicsExtractor g, float x, float y, String eyebrow, String title, float titleScale) {
        ModernStyle.fill(g, Math.round(x), Math.round(y), Math.round(x) + 2, Math.round(y) + 8, ModernStyle.GLOW);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernStyle.spaced(eyebrow), x + 7F, y - 1F, 0.9F, 0xFF9DC4DB);
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY, title, x - 1F, y + 9F, titleScale, 0xFFF2F8FC);
    }

    static void card(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        ModernStyle.darkGlass(g, x, y, w, h, 14, CARD_SURFACE);
        // A hairline of light along the top edge, inset past the corners.
        ModernStyle.fill(g, x + 14, y + 1, x + w - 14, y + 2, 0x2EDDF3FF);
    }

    /** A small rounded tag; returns its width so callers can lay tags out in a row. */
    static int chip(GuiGraphicsExtractor g, int x, int y, String text, int textColor, int fill) {
        int w = Math.round(ModernTypography.width(ModernTypography.Face.TEXT, text, 0.9F)) + 12;
        ModernStyle.rounded(g, x, y, w, 14, 7, fill);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, text, x + 6F, y + 2.4F, 0.9F, textColor);
        return w;
    }

    /**
     * The search field's pill; the vanilla edit box (unbordered, no hint of its own) sits inside after the
     * magnifier. The placeholder is drawn here in the serif, and - unlike vanilla's hint - stays while the
     * empty field has focus.
     */
    static void fieldPill(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean focused, String placeholder, boolean empty) {
        ModernStyle.rounded(g, x, y, w, h, h / 2, focused ? 0x99BFE8FF : 0x33FFFFFF);
        ModernStyle.rounded(g, x + 1, y + 1, w - 2, h - 2, h / 2 - 1, focused ? 0xF2143045 : 0xE00C1E2B);
        ModernIcons.draw(g, ModernIcons.Icon.SEARCH, x + 8F, y + (h - 10) / 2F, 10F, focused ? 0xFFCDEBFF : 0xFF7F9FB4);
        if (empty) {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(placeholder, w - 32), x + 23F, y + h / 2F - 5F, 1F, 0xFF6F8FA4);
        }
    }

    /** Small wide-tracked caps used as a field label above a value. */
    static void label(GuiGraphicsExtractor g, float x, float y, String text) {
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernStyle.spaced(text), x, y, 0.8F, 0xFF6F92A8);
    }
}
