package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyLabelButton;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTextField;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.map.Waypoint;
import com.mentalfrostbyte.jello.map.WaypointColour;
import com.mentalfrostbyte.jello.util.math.Easing;
import java.util.function.Consumer;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The little card that opens where the map was right-clicked: a name, one of seven colours, the spot (two numbers, which can
 * be edited) and Add. It points at the click with a notch on the edge nearest to it - above the card when there is room under
 * the pointer for it, below when there is not - and grows in from 0.8 times with a slight overshoot.
 *
 * <p>The spot is the block under the pointer. What is typed in the spot box replaces it if it reads as coordinates (see
 * {@link Waypoint#parseCoordinates}); an empty name becomes "My waypoint", as the box's own hint says.</p>
 */
final class JelloWaypointPopover {
    static final int W = 214;
    static final int H = 170;
    private static final int NOTCH_W = 47;
    private static final int NOTCH_H = 18;
    private static final int CARD = 0xFFF4F4F4;
    private static final int WHITE = 0xFFFEFEFE;
    private static final int INK = 0xFF010101;
    private static final String DEFAULT_NAME = "My waypoint";

    private final String dimension;
    private final int blockX;
    private final int blockZ;
    private final Consumer<Waypoint> onAdd;
    private final Animation animation = new Animation(250, 120);
    private final LegacyTextField name = new LegacyTextField(LegacyTextField.Style.JELLO, 0, 0, W - 40, 60, Face.JELLO_LIGHT, 25.0F, DEFAULT_NAME);
    private final LegacyTextField spot = new LegacyTextField(LegacyTextField.Style.JELLO, 0, 0, W - 100, 20, Face.JELLO_LIGHT, 18.0F, "x z");
    private final LegacyLabelButton add = new LegacyLabelButton("Add", 0, 0, 40, 50, Face.JELLO_LIGHT, 25.0F, INK);
    private final Animation[] pick = new Animation[WaypointColour.values().length];
    private final boolean above;
    private int x;
    private int y;
    private int chosen;
    private boolean closing;

    /**
     * @param clickX where the pointer was, in canvas pixels
     * @param screenW the canvas' width, to keep the card on it
     * @param screenH the canvas' height, to decide which side of the pointer the card goes
     */
    JelloWaypointPopover(final double clickX, final double clickY, final int screenW, final int screenH,
                         final String dimension, final int blockX, final int blockZ, final Consumer<Waypoint> onAdd) {
        this.dimension = dimension;
        this.blockX = blockX;
        this.blockZ = blockZ;
        this.onAdd = onAdd;
        this.x = (int) Math.max(10, Math.min(screenW - W - 10, clickX - W / 2.0));
        // Below the pointer by default; above it when the card would run off the bottom of the screen.
        int below = (int) clickY + 10 + NOTCH_H;
        this.above = below + H > screenH;
        this.y = this.above ? (int) clickY - 10 - NOTCH_H - H : below;

        this.name.setUnderline(false);
        this.name.setMaxLength(32);
        this.name.setFocused(true);
        this.spot.setUnderline(false);
        this.spot.setText(blockX + " " + blockZ);
        for (int i = 0; i < this.pick.length; i++) {
            this.pick[i] = new Animation(250, 250, i == 0 ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
            this.pick[i].setProgress(i == 0 ? 1.0F : 0.0F);
        }
    }

    boolean closed() {
        return this.closing && this.animation.calcPercent() <= 0.0F;
    }

    boolean closing() {
        return this.closing;
    }

    void close() {
        this.closing = true;
        this.animation.changeDirection(Animation.Direction.BACKWARDS);
    }

    boolean typing() {
        return !this.closing && (this.name.focused() || this.spot.focused());
    }

    boolean contains(final double mx, final double my) {
        return mx >= this.x && mx < this.x + W && my >= this.y - NOTCH_H && my < this.y + H + NOTCH_H;
    }

    // ------------------------------------------------------------------ drawing

    void draw(final LegacyCanvas c, final double mx, final double my, final float alpha) {
        float p = this.animation.calcPercent();
        if (p <= 0.0F) {
            return;
        }

        float pop = Easing.easeOutBack(p, 0.0F, 1.0F, 1.0F);
        float scale = 0.8F + pop * 0.2F;
        // It rises (or, above the click, sinks) into place.
        float slide = Math.round(W * 0.2F * (1.0F - pop)) * (this.above ? -1 : 1);
        float a = alpha * Math.min(1.0F, p * 1.6F);

        c.push();
        try {
            c.scaleAbout(scale, scale, this.x + W / 2.0F, this.above ? this.y + H : this.y);
            c.translate(0, slide);
            c.outerGlow(this.x + 5, this.y + 5, W - 10, H - 10, 20.0F, 0.6F * a);
            c.rounded(this.x, this.y, W, H, 10, LegacyCanvas.alpha(CARD, a));
            // The notch on the side facing the click.
            if (this.above) {
                c.rotated(LegacyTexture.ALT_SELECT, this.x + (W - NOTCH_W) / 2.0F, this.y + H - 1, NOTCH_W, NOTCH_H, 1, LegacyCanvas.alpha(CARD, a));
            } else {
                c.rotated(LegacyTexture.ALT_SELECT, this.x + (W - NOTCH_W) / 2.0F, this.y - NOTCH_H + 1, NOTCH_W, NOTCH_H, 3, LegacyCanvas.alpha(CARD, a));
            }

            this.name.x = this.x + 20;
            this.name.y = this.y + 7;
            this.name.draw(c, a);
            c.fill(this.x + 25, this.y + 68, this.x + W - 25, this.y + 69, LegacyCanvas.alpha(INK, 0.05F * a));

            this.badges(c, a);

            this.spot.x = this.x + 20;
            this.spot.y = this.y + H - 44;
            this.spot.draw(c, a);
            this.add.x = this.x + W - 66;
            this.add.y = this.y + H - 60;
            this.add.draw(c, mx, my, a, !this.closing);
        } finally {
            c.pop();
        }
    }

    /** The seven colours in a row: the chosen one grows a white ring and a faint halo. */
    private void badges(final LegacyCanvas c, final float a) {
        WaypointColour[] colours = WaypointColour.values();
        for (int i = 0; i < colours.length; i++) {
            float chosen = this.pick[i].calcPercent();
            float ring = Easing.easeInOutBack(chosen, 0.0F, 1.0F, 1.0F, 7.0F) * 3.0F;
            float cx = this.x + 25 * (i + 1) + 9;
            float cy = this.y + 86 + 9;
            c.disc(cx, cy, 12.5F, LegacyCanvas.alpha(INK, 0.025F * a * chosen));
            c.disc(cx, cy, 11.5F, LegacyCanvas.alpha(INK, 0.05F * a * chosen));
            c.disc(cx, cy, 9.0F + ring / 2.0F, LegacyCanvas.alpha(WHITE, a * chosen));
            c.disc(cx, cy, 9.0F - ring / 2.0F, LegacyCanvas.fade(colours[i].argb, a));
        }
    }

    // ------------------------------------------------------------------ input

    /** A click inside the card (the caller has checked {@link #contains}). */
    void mouseClicked(final double mx, final double my) {
        if (this.closing) {
            return;
        }

        if (this.add.contains(mx, my)) {
            this.submit();
            return;
        }

        WaypointColour[] colours = WaypointColour.values();
        for (int i = 0; i < colours.length; i++) {
            double cx = this.x + 25 * (i + 1) + 9;
            double cy = this.y + 86 + 9;
            if ((mx - cx) * (mx - cx) + (my - cy) * (my - cy) <= 13 * 13) {
                this.chosen = i;
                for (int j = 0; j < this.pick.length; j++) {
                    this.pick[j].changeDirection(j == i ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
                }
                return;
            }
        }

        boolean inName = this.name.mouseClicked(mx, my);
        boolean inSpot = !inName && this.spot.mouseClicked(mx, my);
        this.name.setFocused(inName);
        this.spot.setFocused(inSpot);
    }

    void mouseDragged(final double mx) {
        if (this.name.focused()) {
            this.name.mouseDragged(mx);
        } else if (this.spot.focused()) {
            this.spot.mouseDragged(mx);
        }
    }

    boolean keyPressed(final KeyEvent event) {
        if (this.closing) {
            return true;
        }

        switch (event.key()) {
            case GLFW.GLFW_KEY_ESCAPE -> this.close();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> this.submit();
            case GLFW.GLFW_KEY_TAB -> {
                boolean toSpot = this.name.focused();
                this.name.setFocused(!toSpot);
                this.spot.setFocused(toSpot);
            }
            default -> {
                if (this.name.focused()) {
                    this.name.keyPressed(event);
                } else if (this.spot.focused()) {
                    this.spot.keyPressed(event);
                }
            }
        }

        return true;
    }

    boolean charTyped(final CharacterEvent event) {
        if (this.closing) {
            return false;
        }

        return this.name.focused() ? this.name.charTyped(event) : this.spot.focused() && this.spot.charTyped(event);
    }

    private void submit() {
        int[] typed = Waypoint.parseCoordinates(this.spot.text());
        int x = typed != null ? typed[0] : this.blockX;
        int z = typed != null ? typed[1] : this.blockZ;
        String text = this.name.text().strip();
        this.onAdd.accept(new Waypoint(text.isEmpty() ? DEFAULT_NAME : text, x, z, WaypointColour.values()[this.chosen].argb, this.dimension));
        this.close();
    }
}
