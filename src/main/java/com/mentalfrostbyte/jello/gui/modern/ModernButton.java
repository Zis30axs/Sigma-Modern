package com.mentalfrostbyte.jello.gui.modern;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * An action button in SigmaModern's page language: a serif label, an optional vector icon, and one of a
 * few kinds - the page's one lit primary action, quiet glass secondaries, a secondary that warms to red on
 * hover for destructive actions, a borderless ghost, and a round icon-only button (its label becomes its
 * tooltip and narration). A real {@link Button}, so Tab, Enter and narration behave like vanilla's.
 */
final class ModernButton extends Button {
    enum Kind { PRIMARY, SECONDARY, DANGER, GHOST, ICON }

    private final Kind kind;
    private final ModernIcons.@Nullable Icon icon;
    private ModernBlocks.@Nullable Block block;
    private float hover;
    private long lastFrame;

    ModernButton(int x, int y, int width, int height, Component label, ModernIcons.@Nullable Icon icon, Kind kind, Runnable action) {
        super(x, y, width, height, label, ignored -> action.run(), DEFAULT_NARRATION);
        this.kind = kind;
        this.icon = icon;
        this.resetTooltip();
    }

    /** Shows a small isometric block in the icon's place. */
    ModernButton withBlock(ModernBlocks.Block block) {
        this.block = block;
        return this;
    }

    /** The width a labelled button needs so {@code label} (and an icon, if any) fits without being cut. */
    static int widthFor(Component label, boolean withIcon) {
        return Math.round(ModernTypography.width(ModernTypography.Face.TEXT, label.getString(), 1F)) + 18 + (withIcon ? 16 : 0);
    }

    /** Back to the default tooltip: an icon-only button's label, nothing for the others. */
    void resetTooltip() {
        this.setTooltip(this.kind == Kind.ICON ? Tooltip.create(this.getMessage()) : null);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        long now = System.nanoTime();
        float dt = this.lastFrame == 0L ? 0F : Math.min(0.05F, (now - this.lastFrame) / 1_000_000_000F);
        this.lastFrame = now;
        boolean lit = this.active && (this.isHovered() || this.isFocused());
        this.hover = ModernStyle.smooth(this.hover, lit ? 1F : 0F, dt, 14F);

        int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
        int radius = this.kind == Kind.ICON ? h / 2 : Math.min(h / 2, 8);
        try (var fade = ModernStyle.alphaScope(this.active ? 1F : 0.42F)) {
            int content;
            switch (this.kind) {
                case PRIMARY -> {
                    ModernStyle.halo(g, x, y, w, h, radius, ModernStyle.GLOW, 0.2F + 0.5F * this.hover);
                    ModernStyle.rounded(g, x, y, w, h, radius, ModernStyle.mix(0xFF237FB6, 0xFF2F9DD8, this.hover));
                    // Sheen inset past the corners, so the gradient's square top edge stays inside them.
                    ModernStyle.fillGradient(g, x + radius, y + 1, x + w - radius, y + h / 2, 0x2EFFFFFF, 0x00FFFFFF);
                    content = 0xFFFFFFFF;
                }
                case SECONDARY, DANGER -> {
                    boolean danger = this.kind == Kind.DANGER;
                    ModernStyle.rounded(g, x, y, w, h, radius, ModernStyle.mix(0x33FFFFFF, danger ? 0xB3FF8A96 : 0x99BFE8FF, this.hover));
                    ModernStyle.rounded(g, x + 1, y + 1, w - 2, h - 2, radius - 1,
                        ModernStyle.mix(0xF20F2230, danger ? 0xF23A1822 : 0xF2173348, this.hover));
                    content = danger ? ModernStyle.mix(ModernStyle.TEXT, 0xFFFFB8C0, this.hover) : ModernStyle.TEXT;
                }
                case GHOST -> {
                    if (this.hover > 0.01F) ModernStyle.rounded(g, x, y, w, h, radius, Math.round(0x22 * this.hover) << 24 | 0xCDEBFF);
                    content = ModernStyle.mix(ModernStyle.MUTED, ModernStyle.TEXT, this.hover);
                }
                default -> {
                    ModernStyle.rounded(g, x, y, w, h, radius, ModernStyle.mix(0x1FFFFFFF, 0x40CDEBFF, this.hover));
                    content = ModernStyle.mix(0xFFC4DDEB, 0xFFFFFFFF, this.hover);
                }
            }

            String label = this.kind == Kind.ICON ? "" : this.getMessage().getString();
            float iconSize = this.kind == Kind.ICON ? Math.min(h - 8F, 14F) : 11F;
            float gap = this.icon != null && !label.isEmpty() ? 5F : 0F;
            float room = w - 14F - (this.icon != null ? iconSize + gap : 0F);
            if (!label.isEmpty()) label = ModernTypography.ellipsize(label, Math.max(8, Math.round(room)));
            float labelW = label.isEmpty() ? 0F : ModernTypography.width(ModernTypography.Face.TEXT, label, 1F);
            float contentW = (this.icon != null ? iconSize : 0F) + gap + labelW;
            float cx = x + (w - contentW) / 2F;
            if (this.block != null) ModernBlocks.draw(g, this.block, cx - 1F, y + (h - iconSize) / 2F - 1F, iconSize + 2F);
            else if (this.icon != null) ModernIcons.draw(g, this.icon, cx, y + (h - iconSize) / 2F, iconSize, content);
            if (!label.isEmpty()) {
                // The serif's caps span roughly 1..9 of its 11-unit line box; center that span, not the box.
                ModernTypography.draw(g, ModernTypography.Face.TEXT, label, cx + (this.icon != null ? iconSize + gap : 0F), y + h / 2F - 5F, 1F, content);
            }
        }
    }
}
