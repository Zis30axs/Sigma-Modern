package com.mentalfrostbyte.jello.gui.legacy;

import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.util.math.Easing;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Jello's pick-one dropdown ({@code Dropdown}): a white bar showing the current choice and a chevron that
 * turns a quarter when it opens; the choices then fold out underneath as light rows that darken under the
 * pointer, in a soft glow. It closes when the pointer leaves it.
 */
public final class LegacyDropdown {
    private static final int TEXT = 0xFF131313;

    public int x;
    public int y;
    public int w;
    public int h;
    private final List<String> values;
    private int selected;
    private boolean open;
    private final Animation fold = new Animation(220, 220, Animation.Direction.BACKWARDS);
    private IntConsumer onSelect = index -> {
    };

    public LegacyDropdown(final int x, final int y, final int w, final int h, final List<String> values, final int selected) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.values = values;
        this.selected = selected;
    }

    public int selected() {
        return this.selected;
    }

    public void onSelect(final IntConsumer listener) {
        this.onSelect = listener;
    }

    public boolean isOpen() {
        return this.open;
    }

    /** The height of the folded-out list right now. */
    private int listHeight() {
        float t = this.open ? Easing.easeOutCubic(this.fold.calcPercent(), 0, 1, 1) : Easing.easeInQuad(this.fold.calcPercent(), 0, 1, 1);
        return (int) ((this.h * this.values.size() + 1) * t);
    }

    public boolean contains(final double mx, final double my) {
        return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h + (this.open ? this.h * this.values.size() : 0);
    }

    public boolean mouseClicked(final double mx, final double my) {
        if (!this.contains(mx, my)) {
            this.close();
            return false;
        }
        if (my < this.y + this.h) {
            this.open = !this.open;
            this.fold.changeDirection(this.open ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
            return true;
        }
        if (this.open) {
            int row = (int) ((my - this.y) / this.h) - 1;
            if (row >= 0 && row < this.values.size()) {
                int before = this.selected;
                this.selected = row;
                this.close();
                if (before != row) {
                    this.onSelect.accept(row);
                }
            }
        }
        return true;
    }

    public void close() {
        this.open = false;
        this.fold.changeDirection(Animation.Direction.BACKWARDS);
    }

    public void draw(final LegacyCanvas c, final double mx, final double my, final float alpha) {
        if (this.open && !this.contains(mx, my)) {
            this.close();
        }
        float fold = this.fold.calcPercent();
        int white = 0xFFFEFEFE;
        int list = this.listHeight();

        if (fold > 0.0F) {
            c.fill(this.x, this.y, this.x + this.w, this.y + this.h, LegacyCanvas.alpha(white, alpha * fold));
            c.outerGlow(this.x, this.y, this.w, this.h + list - 1, 6, alpha * 0.1F * fold);
            c.outerGlow(this.x, this.y, this.w, this.h + list - 1, 20, alpha * 0.2F * fold);
        }
        float textH = c.textHeight(Face.JELLO_LIGHT, 18);
        c.scissor(this.x, this.y, this.x + this.w, this.y + this.h);
        c.text(Face.JELLO_LIGHT, 18, this.values.get(this.selected), this.x + 10, this.y + (this.h - textH) / 2.0F + 1,
            LegacyCanvas.alpha(TEXT, alpha * 0.7F));
        c.unscissor();

        if (fold > 0.0F) {
            c.scissor(this.x, this.y + this.h, this.x + this.w + 140, this.y + this.h + list);
            for (int i = 0; i < this.values.size(); i++) {
                int top = this.y + this.h * (i + 1);
                boolean hover = mx >= this.x && mx < this.x + this.w && my >= top && my < top + this.h;
                int row = LegacyCanvas.shiftTowardsOther(white, 0xFFEAEAEA, hover ? 0.0F : 1.0F);
                c.fill(this.x, top, this.x + this.w, top + this.h, LegacyCanvas.alpha(row, alpha * fold));
                c.text(Face.JELLO_LIGHT, 18, this.values.get(i), this.x + 10 + 10, top + (this.h - textH) / 2.0F + 1,
                    LegacyCanvas.alpha(TEXT, alpha * fold * (i == this.selected ? 1.0F : 0.7F)));
            }
            c.unscissor();
        }

        // The chevron: a ">" that turns a quarter as the list opens.
        float cx = this.x + this.w - (int) (this.h / 2.0F + 0.5F);
        float cy = this.y + (int) (this.h / 2.0F + 0.5F) + 1;
        c.push();
        c.translate(cx, cy);
        c.graphics().pose().rotate((float) (Math.PI / 2 * fold));
        c.translate(-cx, -cy);
        c.text(Face.JELLO_LIGHT, 18, ">", cx - 6, cy - 14, LegacyCanvas.alpha(TEXT, alpha * 0.7F * (this.contains(mx, my) ? 1.0F : 0.5F)));
        c.pop();
    }
}
