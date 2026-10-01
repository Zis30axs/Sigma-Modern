package com.mentalfrostbyte.jello.gui.legacy;

import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.util.math.Easing;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Jello's modal dialog ({@code Alert}): the screen behind blurs, and a white floating card pops up out of the
 * middle with a springy overshoot, holding a big light heading, grey explanatory lines, underlined text
 * fields and full-width blue buttons. It leaves with a short shrink; a press outside the card dismisses it.
 *
 * <p>Rows are declared as the old {@code AlertComponent}s were: a kind, its text and its own height, stacked 10 px
 * apart. The card is the content plus 30 px of margin on every side.</p>
 */
public final class LegacyDialog {
    public enum Kind {
        HEADER, LINE, FIELD, BUTTON
    }

    public record Row(Kind kind, String text, int height) {
    }

    private static final int BLUE = 0xFF3B99FD;
    private static final int GREY = 0xFF999999;
    private static final int INK = 0xFF010101;

    private final int modalWidth;
    private final List<Row> rows;
    private final String[] texts;
    private final LegacyTextField[] fields;
    private final Animation pop = new Animation(285, 100, Animation.Direction.BACKWARDS);
    private final Animation[] buttonHover;
    private final int contentHeight;
    private boolean open;
    private int screenW;
    private int screenH;
    private IntConsumer onButton = index -> {
    };
    private Runnable onClose = () -> {
    };
    private final boolean[] buttonEnabled;

    public LegacyDialog(final int modalWidth, final Row... rows) {
        this.modalWidth = modalWidth;
        this.rows = List.of(rows);
        this.texts = new String[rows.length];
        this.fields = new LegacyTextField[rows.length];
        this.buttonHover = new Animation[rows.length];
        this.buttonEnabled = new boolean[rows.length];
        int height = 0;
        for (int i = 0; i < rows.length; i++) {
            height += rows[i].height() + 10;
            this.texts[i] = rows[i].text();
            if (rows[i].kind() == Kind.FIELD) {
                this.fields[i] = new LegacyTextField(LegacyTextField.Style.JELLO, 0, 0, modalWidth, rows[i].height(), Face.JELLO_LIGHT, 25, rows[i].text());
            }
            if (rows[i].kind() == Kind.BUTTON) {
                this.buttonHover[i] = new Animation(100, 100, Animation.Direction.BACKWARDS);
                this.buttonEnabled[i] = true;
            }
        }
        this.contentHeight = height - 10;
    }

    public boolean isOpen() {
        return this.open;
    }

    /** Still on screen: open, or finishing its exit. */
    public boolean visible() {
        return this.open || this.pop.calcPercent() > 0.0F;
    }

    public void open() {
        this.open = true;
        this.pop.changeDirection(Animation.Direction.FORWARDS);
        for (LegacyTextField field : this.fields) {
            if (field != null) {
                field.setFocused(false);
            }
        }
    }

    public void close() {
        if (this.open) {
            this.open = false;
            this.pop.changeDirection(Animation.Direction.BACKWARDS);
            for (LegacyTextField field : this.fields) {
                if (field != null) {
                    field.setFocused(false);
                }
            }
            this.onClose.run();
        }
    }

    public void onButton(final IntConsumer listener) {
        this.onButton = listener;
    }

    public void onClose(final Runnable listener) {
        this.onClose = listener;
    }

    /** Replaces a HEADER, LINE or BUTTON row's text. */
    public void setText(final int row, final String text) {
        this.texts[row] = text;
    }

    public void setButtonEnabled(final int row, final boolean enabled) {
        this.buttonEnabled[row] = enabled;
    }

    /** The current text of a FIELD row. */
    public String field(final int row) {
        return this.fields[row].text();
    }

    public void setField(final int row, final String text) {
        this.fields[row].setText(text);
    }

    public void clearFields() {
        for (LegacyTextField field : this.fields) {
            if (field != null) {
                field.setText("");
            }
        }
    }

    /** Focuses the first text field, so typing starts straight away. */
    public void focusFirstField() {
        for (LegacyTextField field : this.fields) {
            if (field != null) {
                field.setFocused(true);
                return;
            }
        }
    }

    public boolean typing() {
        if (!this.open) {
            return false;
        }
        for (LegacyTextField field : this.fields) {
            if (field != null && field.focused()) {
                return true;
            }
        }
        return false;
    }

    private float scale(final float p) {
        return this.pop.getDirection() == Animation.Direction.BACKWARDS
            ? 0.5F + Easing.easeOutQuad(p, 0, 1, 1) * 0.5F
            : (float) (Math.pow(2.0, -10.0F * p) * Math.sin((p - 0.25F) * (Math.PI * 2)) + 1.0);
    }

    private int contentX() {
        return (this.screenW - this.modalWidth) / 2;
    }

    private int contentY() {
        return (this.screenH - this.contentHeight) / 2;
    }

    private int rowTop(final int row) {
        int top = 0;
        for (int i = 0; i < row; i++) {
            top += this.rows.get(i).height() + 10;
        }
        return top;
    }

    /** Draws the dialog over the finished screen: blur, wash, card, content. */
    public void draw(final LegacyCanvas c, final double mx, final double my) {
        float p = this.pop.calcPercent();
        if (p == 0.0F) {
            return;
        }
        this.screenW = c.width();
        this.screenH = c.height();
        GuiGraphicsExtractor g = c.graphics();
        // Everything drawn so far is what gets blurred; the wash and the card come after it, sharp.
        g.nextStratum();
        g.blurBeforeThisStratum();

        float alpha = this.open ? Math.min(p / 0.25F, 1.0F) : p;
        float scale = this.scale(p);
        c.fill(0, 0, this.screenW, this.screenH, LegacyCanvas.alpha(INK, 0.1F * alpha));

        int cardW = (int) ((this.modalWidth + 60) * scale);
        int cardH = (int) ((this.contentHeight + 60) * scale);
        if (cardW > 0 && cardH > 0) {
            c.floatingCard((this.screenW - cardW) / 2, (this.screenH - cardH) / 2, cardW, cardH, LegacyCanvas.alpha(0xFFFEFEFE, alpha));
        }

        c.push();
        c.scaleAbout(scale, scale, this.screenW / 2.0F, this.screenH / 2.0F);
        int x = this.contentX();
        int y = this.contentY();
        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            int top = y + this.rowTop(i);
            switch (row.kind()) {
                case HEADER -> c.textCentered(Face.JELLO_LIGHT, fitSize(c, this.texts[i], this.modalWidth, 36, 28, 25, 20, 18, 14, 12),
                    this.texts[i], x + this.modalWidth / 2.0F, top + row.height() / 2.0F, LegacyCanvas.alpha(INK, alpha));
                case LINE -> c.textCentered(Face.JELLO_LIGHT, fitSize(c, this.texts[i], this.modalWidth, 20, 18, 14, 12),
                    this.texts[i], x + this.modalWidth / 2.0F, top + row.height() / 2.0F, LegacyCanvas.alpha(GREY, alpha));
                case FIELD -> {
                    LegacyTextField field = this.fields[i];
                    field.x = x;
                    field.y = top;
                    field.draw(c, alpha);
                }
                case BUTTON -> this.drawButton(c, i, x, top, row.height(), mx, my, alpha);
            }
        }
        c.pop();
    }

    private static int fitSize(final LegacyCanvas c, final String text, final int width, final int... sizes) {
        for (int size : sizes) {
            if (c.textWidth(Face.JELLO_LIGHT, size, text) <= width) {
                return size;
            }
        }
        return sizes[sizes.length - 1];
    }

    private void drawButton(final LegacyCanvas c, final int i, final int x, final int top, final int h, final double mx, final double my, final float alpha) {
        boolean hovered = this.buttonEnabled[i] && mx >= x && mx < x + this.modalWidth && my >= top && my < top + h;
        Animation hover = this.buttonHover[i];
        hover.changeDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        int base = this.buttonEnabled[i] ? BLUE : LegacyCanvas.shiftTowardsOther(BLUE, 0xFFB0B0B0, 0.35F);
        int dark = LegacyCanvas.shiftTowardsOther(base, INK, 0.9F);
        int color = LegacyCanvas.shiftTowardsOther(base, dark, 1.0F - 0.5F * hover.calcPercent());
        c.rounded(x, top, this.modalWidth, h, 4, LegacyCanvas.fade(color, alpha));
        c.textCentered(Face.JELLO_LIGHT, 25, this.texts[i], x + this.modalWidth / 2.0F, top + h / 2.0F, LegacyCanvas.alpha(0xFFFEFEFE, alpha));
    }

    // ------------------------------------------------------------------ input

    /** A press while the dialog is up: always consumed. Returns nothing useful to the caller but closes on outside presses. */
    public void mouseClicked(final double mx, final double my) {
        if (!this.open) {
            return;
        }
        int x = this.contentX();
        int y = this.contentY();
        int cardLeft = x - 30;
        int cardTop = y - 30;
        if (mx < cardLeft || mx >= cardLeft + this.modalWidth + 60 || my < cardTop || my >= cardTop + this.contentHeight + 60) {
            this.close();
            return;
        }
        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            int top = y + this.rowTop(i);
            boolean inside = mx >= x && mx < x + this.modalWidth && my >= top && my < top + row.height();
            if (row.kind() == Kind.FIELD) {
                this.fields[i].x = x;
                this.fields[i].y = top;
                this.fields[i].mouseClicked(mx, my);
            } else if (row.kind() == Kind.BUTTON && inside && this.buttonEnabled[i]) {
                this.onButton.accept(i);
                return;
            }
        }
    }

    public void mouseDragged(final double mx) {
        for (LegacyTextField field : this.fields) {
            if (field != null) {
                field.mouseDragged(mx);
            }
        }
    }

    public boolean keyPressed(final KeyEvent event) {
        if (!this.open) {
            return false;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }
        for (LegacyTextField field : this.fields) {
            if (field != null && field.keyPressed(event)) {
                return true;
            }
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            for (int i = 0; i < this.rows.size(); i++) {
                if (this.rows.get(i).kind() == Kind.BUTTON && this.buttonEnabled[i]) {
                    this.onButton.accept(i);
                    break;
                }
            }
        }
        return true;
    }

    public boolean charTyped(final CharacterEvent event) {
        if (!this.open) {
            return false;
        }
        for (LegacyTextField field : this.fields) {
            if (field != null && field.charTyped(event)) {
                return true;
            }
        }
        return true;
    }
}
