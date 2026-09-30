package com.mentalfrostbyte.jello.gui.legacy;

import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts;

/**
 * A text button whose underline grows out of the middle when the pointer is over it - Jello's
 * {@code TextButton} (the main menu's Exit / Changelog / Switch, the alt manager's Add).
 *
 * <p>The text is centred in the button's rectangle. The line sits 18 px under that centre, is 2 px thick, and
 * grows to the text's width over {@code 210 * sqrt(width / 242)} ms with a cubic ease (so most of the growth is
 * at the end), and shrinks the same way when the pointer leaves.</p>
 */
public final class LegacyLabelButton {
    public int x;
    public int y;
    public int w;
    public int h;
    public String text;
    private final LegacyFonts.Face face;
    private final float size;
    private final int color;
    private final Animation line;

    public LegacyLabelButton(
        final String text, final int x, final int y, final int w, final int h,
        final LegacyFonts.Face face, final float size, final int color
    ) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.face = face;
        this.size = size;
        this.color = color;
        int duration = (int) (210.0 * Math.sqrt(w / 242.0F));
        this.line = new Animation(Math.max(1, duration), Math.max(1, duration), Animation.Direction.BACKWARDS);
    }

    public boolean contains(final double mx, final double my) {
        return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
    }

    /** {@code alpha} multiplies the colour's own alpha; {@code active} lets the caller hold the line down. */
    public void draw(final LegacyCanvas c, final double mx, final double my, final float alpha, final boolean active) {
        this.line.changeDirection(active && this.contains(mx, my) ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        int color = LegacyCanvas.fade(this.color, alpha);
        float cx = this.x + this.w / 2.0F;
        float cy = this.y + this.h / 2.0F;
        float textW = c.textWidth(this.face, this.size, this.text);
        c.textCentered(this.face, this.size, this.text, cx, cy, color);
        float grow = (float) Math.pow(this.line.calcPercent(), 3.0);
        if (grow > 0.0F) {
            c.fill(Math.round(cx - textW / 2.0F * grow), Math.round(cy + 18), Math.round(cx + textW / 2.0F * grow), Math.round(cy + 20), color);
        }
    }
}
