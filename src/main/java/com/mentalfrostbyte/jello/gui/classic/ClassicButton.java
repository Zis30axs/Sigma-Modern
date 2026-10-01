package com.mentalfrostbyte.jello.gui.classic;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;

/**
 * Classic's flat button ({@code AltManagerButton}): a translucent slab in the given colour with a thin frame and
 * the label in Minecraft's font at twice its size. It lightens under the pointer and again while held; a
 * disabled one is dimmer and its label half as strong. The colour is black on the alt manager's toolbar and
 * grey on its sub-screens.
 */
public final class ClassicButton {
    public int x;
    public int y;
    public int w;
    public int h;
    public String text;
    public boolean enabled = true;
    private final int color;

    public ClassicButton(final String text, final int x, final int y, final int w, final int h, final int color) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.color = color;
    }

    public boolean contains(final double mx, final double my) {
        return this.enabled && mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
    }

    /** {@code held} is whether the primary button is down (a press that started here is what counts, the caller's business). */
    public void draw(final LegacyCanvas c, final double mx, final double my, final boolean held) {
        boolean hover = this.contains(mx, my);
        float fill = !this.enabled ? 0.25F : !hover ? 0.4F : held ? 0.6F : 0.5F;
        c.fill(this.x, this.y, this.x + this.w, this.y + this.h, LegacyCanvas.alpha(this.color, fill));
        frame(c, this.x, this.y, this.x + this.w, this.y + this.h, 2, LegacyCanvas.alpha(this.color, 0.2F));
        c.vanilla(this.text, this.x + this.w / 2.0F, this.y + this.h / 2.0F - 9, LegacyCanvas.alpha(0xFFFEFEFE, this.enabled ? 1.0F : 0.5F), false, true);
    }

    /** The old {@code method11429}: a frame {@code thickness} px wide just inside the rectangle. */
    public static void frame(final LegacyCanvas c, final int x0, final int y0, final int x1, final int y1, final int thickness, final int color) {
        c.fill(x0, y1 - thickness, x1 - thickness, y1, color);
        c.fill(x0, y0, x1 - thickness, y0 + thickness, color);
        c.fill(x0, y0 + thickness, x0 + thickness, y1 - thickness, color);
        c.fill(x1 - thickness, y0, x1, y1, color);
    }
}
