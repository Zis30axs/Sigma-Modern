package com.mentalfrostbyte.jello.gui.legacy;

import com.mentalfrostbyte.jello.gui.modern.LegacyFonts;
import com.mentalfrostbyte.jello.gui.modern.LegacyShapes;
import com.mentalfrostbyte.jello.gui.modern.ModernStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Draws in the old client's units: one unit is one framebuffer pixel, whatever the GUI scale.
 *
 * <p>Jello and Classic were laid out against the raw window size ({@code getMainWindow().getWidth()}), and
 * 26.2's {@code Screen.width/height} are that divided by the GUI scale. Instead of converting every measurement
 * at the edge, a screen wraps its drawing in one of these: the pose is scaled by {@code 1 / guiScale}, so the
 * old numbers ({@code x + 110, y + 18}, a 336 px logo) are used as they were and land on the same pixels.
 * Hit testing uses {@link #mouseX()} / {@link #mouseY()}, which are in the same units.</p>
 *
 * <pre>{@code try (LegacyCanvas c = new LegacyCanvas(graphics)) { c.image(LegacyTexture.JELLO_LOGO, ...); }}</pre>
 */
public final class LegacyCanvas implements AutoCloseable {
    private final GuiGraphicsExtractor graphics;
    private final int guiScale;

    public LegacyCanvas(final GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
        this.guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        graphics.pose().pushMatrix();
        graphics.pose().scale(1.0F / this.guiScale, 1.0F / this.guiScale);
    }

    @Override
    public void close() {
        this.graphics.pose().popMatrix();
    }

    public GuiGraphicsExtractor graphics() {
        return this.graphics;
    }

    /** The screen's width in framebuffer pixels. */
    public int width() {
        return this.graphics.guiWidth() * this.guiScale;
    }

    public int height() {
        return this.graphics.guiHeight() * this.guiScale;
    }

    public int guiScale() {
        return this.guiScale;
    }

    /** The pointer's x in framebuffer pixels (fractional, as the window reports it). */
    public static double mouseX() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.getScaledXPos(mc.getWindow()) * Math.max(1, mc.getWindow().getGuiScale());
    }

    public static double mouseY() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.getScaledYPos(mc.getWindow()) * Math.max(1, mc.getWindow().getGuiScale());
    }

    /** Converts a GUI-unit coordinate (an event's {@code x()}) to framebuffer pixels. */
    public static double toLegacy(final double guiUnits) {
        return guiUnits * Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    // ------------------------------------------------------------------ colours

    /** The old {@code RenderUtil2.applyAlpha}: keeps the colour, replaces the alpha. */
    public static int alpha(final int color, final float alpha) {
        return (int) (Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F) << 24 | color & 0x00FFFFFF;
    }

    /** Multiplies {@code alpha} into the colour's own alpha. */
    public static int fade(final int color, final float alpha) {
        return Math.round((color >>> 24) * Math.max(0.0F, Math.min(1.0F, alpha))) << 24 | color & 0x00FFFFFF;
    }

    /** The old {@code RenderUtil2.shiftTowardsOther}: {@code shift} of 1 is all {@code color}, 0 all {@code other}. */
    public static int shiftTowardsOther(final int color, final int other, final float shift) {
        return ModernStyle.mix(other, color, shift);
    }

    // ------------------------------------------------------------------ images

    public void image(final LegacyTexture texture, final float x, final float y, final float w, final float h, final int color) {
        this.image(texture.id, texture.width, texture.height, x, y, w, h, color);
    }

    public void image(final LegacyTexture texture, final float x, final float y, final float w, final float h) {
        this.image(texture.id, texture.width, texture.height, x, y, w, h, 0xFFFFFFFF);
    }

    /** A whole texture stretched to a float rectangle. */
    public void image(final Identifier id, final int texW, final int texH, final float x, final float y, final float w, final float h, final int color) {
        this.region(id, texW, texH, 0, 0, texW, texH, x, y, w, h, color);
    }

    /** The {@code (u, v, uw, vh)} texel rectangle of a texture stretched to a float rectangle. */
    public void region(
        final Identifier id, final int texW, final int texH,
        final float u, final float v, final int uw, final int vh,
        final float x, final float y, final float w, final float h, final int color
    ) {
        if ((color >>> 24) == 0 || w <= 0 || h <= 0) {
            return;
        }
        this.graphics.pose().pushMatrix();
        try {
            this.graphics.pose().translate(x, y);
            this.graphics.pose().scale(w / uw, h / vh);
            this.graphics.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, u, v, uw, vh, uw, vh, texW, texH, color);
        } finally {
            this.graphics.pose().popMatrix();
        }
    }

    /** A texture turned {@code quarterTurns} times clockwise, filling the rectangle as it appears after the turn. */
    public void rotated(final LegacyTexture texture, final float x, final float y, final float w, final float h, final int quarterTurns, final int color) {
        this.rotatedRegion(texture, 0, 0, texture.width, texture.height, x, y, w, h, quarterTurns, color);
    }

    /**
     * The {@code (u, v, uw, vh)} texel rectangle of a texture, turned {@code quarterTurns} times clockwise and
     * stretched to fill {@code (x, y, w, h)} as it appears after the turn.
     */
    public void rotatedRegion(
        final LegacyTexture texture, final int u, final int v, final int uw, final int vh,
        final float x, final float y, final float w, final float h, final int quarterTurns, final int color
    ) {
        if ((color >>> 24) == 0 || w <= 0 || h <= 0) {
            return;
        }
        boolean odd = (quarterTurns & 1) != 0;
        float beforeW = odd ? h : w;
        float beforeH = odd ? w : h;
        this.graphics.pose().pushMatrix();
        try {
            this.graphics.pose().translate(x + w / 2.0F, y + h / 2.0F);
            this.graphics.pose().rotate((float) (quarterTurns * Math.PI / 2.0));
            this.graphics.pose().scale(beforeW / uw, beforeH / vh);
            this.graphics.pose().translate(-uw / 2.0F, -vh / 2.0F);
            this.graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id, 0, 0, u, v, uw, vh, uw, vh, texture.width, texture.height, color);
        } finally {
            this.graphics.pose().popMatrix();
        }
    }

    // ------------------------------------------------------------------ shapes

    public void fill(final int x0, final int y0, final int x1, final int y1, final int color) {
        if ((color >>> 24) != 0) {
            this.graphics.fill(x0, y0, x1, y1, color);
        }
    }

    /**
     * A gradient running left to right (the old {@code drawQuad} with its corners set that way). It is cut into
     * strips of about a colour step each, which at 8 bits a channel cannot be told from a smooth one.
     */
    public void gradientH(final int x0, final int y0, final int x1, final int y1, final int left, final int right) {
        int width = x1 - x0;
        if (width <= 0 || y1 <= y0) {
            return;
        }

        int strips = Math.min(width, 64);
        for (int i = 0; i < strips; i++) {
            int from = x0 + width * i / strips;
            int to = x0 + width * (i + 1) / strips;
            this.fill(from, y0, to, y1, ModernStyle.mix(left, right, (i + 0.5F) / strips));
        }
    }

    /**
     * A triangle pointing left: its tip at {@code (tipX, cy)}, {@code size} wide and {@code size} tall, in one-pixel
     * columns (the old {@code renderCategoryBox}, which drew it with GL_TRIANGLES).
     */
    public void pointerLeft(final float tipX, final float cy, final float size, final int color) {
        int columns = Math.round(size);
        for (int j = 0; j < columns; j++) {
            float half = size / 2.0F * (j + 0.5F) / columns;
            this.fill(Math.round(tipX) + j, Math.round(cy - half), Math.round(tipX) + j + 1, Math.round(cy + half), color);
        }
    }

    public void rounded(final int x, final int y, final int w, final int h, final int radius, final int color) {
        ModernStyle.rounded(this.graphics, x, y, w, h, radius, color);
    }

    public void disc(final float cx, final float cy, final float radius, final int color) {
        LegacyShapes.disc(this.graphics, cx, cy, radius, color);
    }

    // ------------------------------------------------------------------ text

    public void text(final LegacyFonts.Face face, final float size, final String text, final float x, final float y, final int color) {
        LegacyFonts.draw(this.graphics, face, text, x, y, size, color);
    }

    /** Centred on {@code cx} horizontally and on {@code cy} vertically, as the old {@code NEGATE_AND_DIVIDE_BY_2}. */
    public void textCentered(final LegacyFonts.Face face, final float size, final String text, final float cx, final float cy, final int color) {
        float w = LegacyFonts.width(face, text, size);
        float h = LegacyFonts.height(face, size);
        LegacyFonts.draw(this.graphics, face, text, cx - w / 2.0F, cy - h / 2.0F, size, color);
    }

    /**
     * Minecraft's own font at the old {@code DefaultClientFont}'s 2x: Classic's text. {@code (x, y)} is the
     * top-left, or with {@code centered} the middle of the text horizontally.
     */
    public void vanilla(final Component text, final float x, final float y, final int color, final boolean shadow, final boolean centered) {
        Font font = Minecraft.getInstance().font;
        float scale = 2.0F;
        float left = centered ? x - font.width(text) * scale / 2.0F : x;
        this.graphics.pose().pushMatrix();
        try {
            this.graphics.pose().translate(left, y);
            this.graphics.pose().scale(scale, scale);
            this.graphics.text(font, text, 0, 0, color, shadow);
        } finally {
            this.graphics.pose().popMatrix();
        }
    }

    public void vanilla(final String text, final float x, final float y, final int color, final boolean shadow, final boolean centered) {
        this.vanilla(Component.literal(text), x, y, color, shadow, centered);
    }

    public float vanillaWidth(final String text) {
        return Minecraft.getInstance().font.width(text) * 2.0F;
    }

    public float textWidth(final LegacyFonts.Face face, final float size, final String text) {
        return LegacyFonts.width(face, text, size);
    }

    public float textHeight(final LegacyFonts.Face face, final float size) {
        return LegacyFonts.height(face, size);
    }

    // ------------------------------------------------------------------ state

    public void push() {
        this.graphics.pose().pushMatrix();
    }

    public void pop() {
        this.graphics.pose().popMatrix();
    }

    public void translate(final float x, final float y) {
        this.graphics.pose().translate(x, y);
    }

    public void scale(final float sx, final float sy) {
        this.graphics.pose().scale(sx, sy);
    }

    /** Scales about a point, as the old {@code translate, scale, translate back} triple. */
    public void scaleAbout(final float sx, final float sy, final float px, final float py) {
        this.graphics.pose().translate(px, py);
        this.graphics.pose().scale(sx, sy);
        this.graphics.pose().translate(-px, -py);
    }

    public void scissor(final int x0, final int y0, final int x1, final int y1) {
        this.graphics.enableScissor(x0, y0, x1, y1);
    }

    public void unscissor() {
        this.graphics.disableScissor();
    }

    // ------------------------------------------------------------------ Jello panels

    /**
     * Jello's floating card: a flat body whose edges feather out through the corner and border textures, so a
     * card sits on the scene as a soft, slightly glowing sheet rather than a hard rectangle. The feather reaches
     * 26 px outside {@code (x, y, w, h)}. This is the old {@code RenderUtil.method11467}.
     */
    public void floatingCard(final int x, final int y, final int w, final int h, final int color) {
        final int size = 36;
        final int inset = 10;
        final int reach = size - inset;
        // One pixel of overlap under the feathered edge, so no seam shows where the body meets it.
        this.fill(x + inset - 1, y + inset - 1, x + w - inset + 1, y + h - inset + 1, color);
        this.image(LegacyTexture.FLOATING_CORNER, x - reach, y - reach, size, size, color);
        this.rotated(LegacyTexture.FLOATING_CORNER, x + w - inset, y - reach, size, size, 1, color);
        this.rotated(LegacyTexture.FLOATING_CORNER, x + w - inset, y + h - inset, size, size, 2, color);
        this.rotated(LegacyTexture.FLOATING_CORNER, x - reach, y + h - inset, size, size, 3, color);

        // Edge tiles, the last one cut short rather than clipped: a scissor rounds outward at odd GUI scales and
        // would draw a row twice against the corner beside it.
        for (int k = 0; k < h - 2 * inset; k += size) {
            int len = Math.min(size, h - 2 * inset - k);
            this.region(LegacyTexture.FLOATING_BORDER.id, size, size, 0, 0, size, len, x - reach, y + inset + k, size, len, color);
            this.rotatedRegion(LegacyTexture.FLOATING_BORDER, 0, size - len, size, len, x + w - inset, y + inset + k, size, len, 2, color);
        }
        for (int k = 0; k < w - 2 * inset; k += size) {
            int len = Math.min(size, w - 2 * inset - k);
            this.rotatedRegion(LegacyTexture.FLOATING_BORDER, 0, size - len, size, len, x + inset + k, y - reach, len, size, 1, color);
            this.rotatedRegion(LegacyTexture.FLOATING_BORDER, 0, 0, size, len, x + inset + k, y + h - inset, len, size, 3, color);
        }
    }

    /** A soft glow outside {@code (x, y, w, h)}, {@code size} px deep (the old six-argument {@code drawRoundedRect}). */
    public void outerGlow(final float x, final float y, final float w, final float h, final float size, final float alpha) {
        int color = alpha(0xFFFEFEFE, alpha);
        this.image(LegacyTexture.SHADOW_CORNER_1, x - size, y - size, size, size, color);
        this.image(LegacyTexture.SHADOW_CORNER_2, x + w, y - size, size, size, color);
        this.image(LegacyTexture.SHADOW_CORNER_3, x - size, y + h, size, size, color);
        this.image(LegacyTexture.SHADOW_CORNER_4, x + w, y + h, size, size, color);
        this.image(LegacyTexture.SHADOW_LEFT, x - size, y, size, h, color);
        this.image(LegacyTexture.SHADOW_RIGHT, x + w, y, size, h, color);
        this.image(LegacyTexture.SHADOW_TOP, x, y - size, w, size, color);
        this.image(LegacyTexture.SHADOW_BOTTOM, x, y + h, w, size, color);
    }

    /** The same feather drawn inside the rectangle, fading its content edges into the backdrop (old {@code method11464}). */
    public void innerFeather(final float x, final float y, final float w, final float h, final float size, final float alpha) {
        int color = alpha(0xFFFEFEFE, alpha);
        this.image(LegacyTexture.SHADOW_RIGHT, x, y, size, h, color);
        this.image(LegacyTexture.SHADOW_LEFT, x + w - size, y, size, h, color);
        this.image(LegacyTexture.SHADOW_BOTTOM, x, y, w, size, color);
        this.image(LegacyTexture.SHADOW_TOP, x, y + h - size, w, size, color);
    }
}
