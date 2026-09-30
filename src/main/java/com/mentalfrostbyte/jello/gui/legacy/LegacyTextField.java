package com.mentalfrostbyte.jello.gui.legacy;

import com.mentalfrostbyte.jello.gui.modern.LegacyFonts;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * The old client's single-line text field ({@code TextField}), with 26.2's own editing behind it.
 *
 * <p>Editing - caret, selection, word jumps, copy/cut/paste, select-all - is {@link TextFieldHelper}'s, the same
 * code the sign and book editors use, so it behaves as the game's fields do. Drawing is the old field's:</p>
 * <ul>
 *   <li>{@link Style#JELLO}: no box, just the text on a 2 px underline; the caret blinks on a one-second beat,
 *       selections are a pale blue band, the placeholder is half as strong as typed text, and focusing lifts
 *       the line and the text a little.</li>
 *   <li>{@link Style#CLASSIC}: a black box with a 2 px light frame and white text.</li>
 * </ul>
 * <p>Long text scrolls so the caret stays in view, easing toward its position.</p>
 */
public final class LegacyTextField {
    public enum Style {
        JELLO, CLASSIC
    }

    public int x;
    public int y;
    public int w;
    public int h;
    private final Style style;
    private final LegacyFonts.Face face;
    private final float size;
    private final TextFieldHelper helper;
    private String text = "";
    private String placeholder = "";
    private boolean censor;
    private int ink;
    private boolean focused;
    private int maxLength = 256;
    private float scroll;
    private float focusFade;
    private long lastDrawNanos;
    private long blinkStartNanos = System.nanoTime();
    private Consumer<LegacyTextField> onChange = field -> {
    };

    public LegacyTextField(
        final Style style, final int x, final int y, final int w, final int h, final LegacyFonts.Face face, final float size, final String placeholder
    ) {
        this.style = style;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.face = face;
        this.size = size;
        this.placeholder = placeholder;
        Minecraft mc = Minecraft.getInstance();
        this.helper = new TextFieldHelper(
            () -> this.text,
            value -> {
                if (value.length() <= this.maxLength && !value.equals(this.text)) {
                    this.text = value;
                    this.onChange.accept(this);
                }
            },
            TextFieldHelper.createClipboardGetter(mc),
            TextFieldHelper.createClipboardSetter(mc),
            value -> value.length() <= this.maxLength
        );
    }

    public String text() {
        return this.text;
    }

    public void setText(final String text) {
        this.text = text == null ? "" : text;
        this.helper.setCursorToEnd();
        this.onChange.accept(this);
    }

    public void onChange(final Consumer<LegacyTextField> listener) {
        this.onChange = listener;
    }

    public void setCensored(final boolean censor) {
        this.censor = censor;
    }

    /** The colour of the text and caret, for a field over a dark backdrop; the style's own (dark on Jello) when unset. */
    public void setInk(final int argb) {
        this.ink = argb;
    }

    public void setMaxLength(final int maxLength) {
        this.maxLength = maxLength;
    }

    public boolean focused() {
        return this.focused;
    }

    public void setFocused(final boolean focused) {
        if (this.focused != focused) {
            this.focused = focused;
            this.blinkStartNanos = System.nanoTime();
            Minecraft.getInstance().textInputManager().onTextInputFocusChange(focused);
        }
    }

    public boolean contains(final double mx, final double my) {
        return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
    }

    /** A press: focuses the field when it is inside (placing the caret there) and releases it otherwise. */
    public boolean mouseClicked(final double mx, final double my) {
        boolean inside = this.contains(mx, my);
        this.setFocused(inside);
        if (inside) {
            this.helper.setCursorPos(this.indexAt(mx), false);
        }
        return inside;
    }

    public void mouseDragged(final double mx) {
        if (this.focused) {
            this.helper.setCursorPos(this.indexAt(mx), true);
        }
    }

    public boolean keyPressed(final KeyEvent event) {
        return this.focused && this.helper.keyPressed(event);
    }

    public boolean charTyped(final CharacterEvent event) {
        return this.focused && this.helper.charTyped(event);
    }

    private String shown() {
        return this.censor ? "·".repeat(this.text.length()) : this.text;
    }

    private float width(final String s) {
        return LegacyFonts.width(this.face, s, this.size);
    }

    private int indexAt(final double mx) {
        String shown = this.shown();
        float local = (float) (mx - (this.x + 4) - this.scroll);
        int best = 0;
        float bestGap = Float.MAX_VALUE;
        for (int i = 0; i <= shown.length(); i++) {
            float gap = Math.abs(this.width(shown.substring(0, i)) - local);
            if (gap < bestGap) {
                bestGap = gap;
                best = i;
            }
        }
        return best;
    }

    public void draw(final LegacyCanvas c, final float alpha) {
        long now = System.nanoTime();
        float dt = this.lastDrawNanos == 0 ? 0 : Math.min(0.1F, (now - this.lastDrawNanos) / 1.0E9F);
        this.lastDrawNanos = now;
        this.focusFade = Math.max(0.0F, Math.min(1.0F, this.focusFade + (this.focused ? 6.0F : -6.0F) * dt));

        String shown = this.shown();
        int cursor = Math.min(this.helper.getCursorPos(), shown.length());
        int selection = Math.min(this.helper.getSelectionPos(), shown.length());
        boolean jello = this.style == Style.JELLO;
        int ink = this.ink != 0 ? this.ink : jello ? 0xFF010101 : 0xFFFEFEFE;
        int inner = this.x + 4;
        int innerWidth = this.w - 8;

        if (!jello) {
            c.fill(this.x, this.y, this.x + this.w, this.y + this.h, LegacyCanvas.fade(0xFF010101, alpha));
            int frame = LegacyCanvas.fade(LegacyCanvas.shiftTowardsOther(0xFFFEFEFE, 0xFF010101, 0.23F), alpha);
            c.fill(this.x - 2, this.y, this.x + this.w + 2, this.y + 2, frame);
            c.fill(this.x - 2, this.y + this.h - 2, this.x + this.w + 2, this.y + this.h, frame);
            c.fill(this.x - 2, this.y + 2, this.x, this.y + this.h - 2, frame);
            c.fill(this.x + this.w, this.y + 2, this.x + this.w + 2, this.y + this.h - 2, frame);
        }

        // Keep the caret in view, easing toward where it needs to be.
        float caretX = this.width(shown.substring(0, cursor));
        float target = this.scroll;
        if (caretX + target < 0) {
            target = -caretX;
        } else if (caretX + target > innerWidth) {
            target = innerWidth - caretX;
        }
        if (this.width(shown) + target < innerWidth) {
            target = Math.min(0, innerWidth - this.width(shown));
        }
        this.scroll += (target - this.scroll) / 2.0F;

        float textH = LegacyFonts.height(this.face, this.size);
        float textY = this.y + this.h / 2.0F - textH / 2.0F;
        c.scissor(this.x, this.y, this.x + this.w, this.y + this.h);
        if (this.focused && cursor != selection) {
            float a = inner + this.scroll + this.width(shown.substring(0, Math.min(cursor, selection)));
            float b = inner + this.scroll + this.width(shown.substring(0, Math.max(cursor, selection)));
            c.fill(Math.round(a), Math.round(textY), Math.round(b), Math.round(textY + textH), LegacyCanvas.fade(0xFFABD2FE, alpha));
        }
        boolean placeholderShown = shown.isEmpty();
        float textAlpha = (this.focusFade / 2.0F + 0.4F) * alpha * (this.focused && !shown.isEmpty() ? 1.0F : 0.5F);
        c.text(this.face, this.size, placeholderShown ? this.placeholder : shown, inner + this.scroll, textY, LegacyCanvas.alpha(ink, textAlpha));
        if (this.focused) {
            boolean on = (now - this.blinkStartNanos) / 1_000_000L % 1000L >= 500L;
            float cx = inner + this.scroll + caretX;
            c.fill(Math.round(cx) - (shown.isEmpty() ? 0 : 1), Math.round(textY) + 2, Math.round(cx) + (shown.isEmpty() ? 1 : 0), Math.round(textY + textH) - 1,
                LegacyCanvas.alpha(ink, on ? 0.8F : 0.1F * alpha));
        }
        c.unscissor();

        if (jello) {
            c.fill(this.x, this.y + this.h - 2, this.x + this.w, this.y + this.h, LegacyCanvas.fade(0xCACACACA, (this.focusFade / 2.0F + 0.5F) * alpha));
        }
    }
}
