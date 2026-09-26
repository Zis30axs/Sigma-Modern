package com.mentalfrostbyte.jello.gui.modern;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * SigmaModern's chrome for vanilla widgets and screens, so the sub-pages Modern hands off to (video, sound,
 * controls, world creation, confirmations...) read as part of the same client instead of dropping back to
 * stone-grey sprites. Vanilla keeps its layout, text (in its own font, which its widths are laid out for)
 * and behavior; the marked hooks in {@code AbstractButton}, {@code AbstractSliderButton}, {@code EditBox},
 * {@code Checkbox}, {@code AbstractSelectionList}, {@code AbstractScrollArea} and {@code Screen} call in
 * here instead of blitting their sprites while SigmaModern is active.
 */
public final class ModernSkin {
    private static final long CLOCK_START = System.nanoTime();

    private ModernSkin() {}

    public static boolean active() {
        return ModernScreens.active();
    }

    public static void button(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean enabled, boolean highlighted, float alpha) {
        try (var fade = ModernStyle.alphaScope(alpha * (enabled ? 1F : 0.55F))) {
            int r = Math.max(1, Math.min(h / 2, 7));
            ModernStyle.rounded(g, x, y, w, h, r, highlighted ? 0xB3BFE8FF : 0x40FFFFFF);
            ModernStyle.rounded(g, x + 1, y + 1, w - 2, h - 2, r - 1, highlighted ? 0xF2173348 : 0xE60E202D);
            if (highlighted) ModernStyle.fillGradient(g, x + r, y + 1, x + w - r, y + h / 2, 0x1FFFFFFF, 0x00FFFFFF);
        }
    }

    /** A glass track, the part left of the handle tinted with the accent, and a light handle where vanilla's is. */
    public static void slider(GuiGraphicsExtractor g, int x, int y, int w, int h, double value, boolean enabled, boolean highlighted, float alpha) {
        try (var fade = ModernStyle.alphaScope(alpha * (enabled ? 1F : 0.55F))) {
            int r = Math.max(1, Math.min(h / 2, 7));
            ModernStyle.rounded(g, x, y, w, h, r, highlighted ? 0x99BFE8FF : 0x40FFFFFF);
            ModernStyle.rounded(g, x + 1, y + 1, w - 2, h - 2, r - 1, 0xE60E202D);
            int handleX = x + (int)(value * (w - 8));
            int filled = handleX + 4 - (x + 1);
            if (filled > 2) ModernStyle.rounded(g, x + 1, y + 1, filled, h - 2, Math.min(r - 1, filled / 2), 0x4D2E9BD6);
            // Vanilla centers the label over the track, so the handle stays slim and soft enough to read through.
            ModernStyle.rounded(g, handleX + 2, y + 3, 4, h - 6, 2, highlighted ? 0xE6FFFFFF : 0x99BFDCEC);
        }
    }

    public static void field(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean enabled, boolean focused) {
        try (var fade = ModernStyle.alphaScope(enabled ? 1F : 0.6F)) {
            int r = Math.max(1, Math.min(h / 2, 6));
            ModernStyle.rounded(g, x, y, w, h, r, focused ? 0x99BFE8FF : 0x38FFFFFF);
            ModernStyle.rounded(g, x + 1, y + 1, w - 2, h - 2, r - 1, focused ? 0xF2112838 : 0xE6091722);
        }
    }

    public static void checkbox(GuiGraphicsExtractor g, int x, int y, int size, boolean selected, boolean focused, float alpha) {
        try (var fade = ModernStyle.alphaScope(alpha)) {
            ModernStyle.rounded(g, x, y, size, size, 4, focused ? 0xB3BFE8FF : 0x4DFFFFFF);
            ModernStyle.rounded(g, x + 1, y + 1, size - 2, size - 2, 3, selected ? 0xFF2586C0 : 0xE60E202D);
            if (selected) ModernIcons.draw(g, ModernIcons.Icon.CHECK, x + 2F, y + 2F, size - 4F, 0xFFFFFFFF);
        }
    }

    /** The selected row: a soft ice band with the accent bar used for "selected" everywhere in Modern. */
    public static void listSelection(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean focused) {
        // Reaches a little left of the row, so the accent bar sits clear of a row's leading icon.
        ModernStyle.rounded(g, x - 6, y, w + 8, h, 7, focused ? 0x33CDEBFF : 0x24CDEBFF);
        ModernStyle.rounded(g, x - 5, y + 8, 2, Math.max(2, h - 16), 1, ModernStyle.GLOW);
    }

    /** A menu tab: quiet until hovered, an ice pill when selected (vanilla draws the underline and label). */
    public static void tab(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean selected, boolean highlighted, boolean enabled) {
        try (var fade = ModernStyle.alphaScope(enabled ? 1F : 0.55F)) {
            int inset = 2;
            if (selected) {
                ModernStyle.rounded(g, x + inset, y + 3, w - 2 * inset, h - 3, 8, 0x38CDEBFF);
            } else if (highlighted) {
                ModernStyle.rounded(g, x + inset, y + 5, w - 2 * inset, h - 7, 7, 0x1FFFFFFF);
            }
        }
    }

    /** A hairline thumb centered in vanilla's scrollbar column, widening under the pointer. */
    public static void scrollbar(GuiGraphicsExtractor g, int x, int y, int w, int h, int thumbY, int thumbH, boolean hot) {
        int cx = x + w / 2;
        ModernStyle.rounded(g, cx - 1, y, 2, h, 1, 0x14FFFFFF);
        int tw = hot ? 4 : 3;
        ModernStyle.rounded(g, cx - tw / 2, thumbY, tw, thumbH, 2, hot ? 0xCCDDF3FF : 0x73DDF3FF);
    }

    /**
     * Replaces the title panorama behind vanilla menus with the painted night, ending its stratum so the
     * screen's own blur pass (which only blurs earlier strata) softens it.
     */
    public static void panorama(GuiGraphicsExtractor g, int width, int height) {
        float time = (System.nanoTime() - CLOCK_START) / 1_000_000_000F;
        ModernScene.shared().render(g, width, height, 0F, 0F, time);
        g.nextStratum();
    }

    /** The cold tint that replaces vanilla's menu background texture over the (blurred) scene or world. */
    public static void menuBackground(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean inWorld) {
        ModernStyle.fillGradient(g, x, y, x + w, y + h, inWorld ? 0x66050F1A : 0x52050F1A, inWorld ? 0x99050F1A : 0x8C050F1A);
    }
}
