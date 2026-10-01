package com.mentalfrostbyte.jello.gui.legacy.hud;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList.Outline;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList.Transition;
import com.mentalfrostbyte.jello.util.math.Easing;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Classic's module list ({@code ActiveMods}): a stack of dark boxes down the right edge, each with the module's name
 * in SF UI Display Bold and its mode after it in grey, the names in a hue that runs down the list and round the
 * colour wheel every two seconds. The bar on the box edges is in the same hue.
 *
 * <p>The boxes are as wide as their text and the widest is first. A module switched on grows its box out of nothing
 * and pushes the ones below down, or slides in from the edge, or both ({@link Transition}); the bar along the left
 * edge and the cap over each box step in to meet the box above, so the edge follows the boxes' widths down.</p>
 */
final class ClassicActiveMods {
    private static final Face NAME_FACE = Face.CLASSIC_BOLD;
    private static final float NAME_SIZE = 18.0F;
    private static final float MODE_SIZE = 16.0F;
    /** Rows are the face's height less two pixels, so their boxes touch. */
    private static final int ROW_TRIM = -2;
    private static final int BOX = 0x96000000;
    private static final int MODE_COLOR = 0xFFA0A0A0;
    /** The hue steps 5/255 of the wheel from one row to the next. */
    private static final float HUE_STEP = 0.0196078431372549F;

    /** How hidden a module's box is: 0 when it is shown, 1 when it is gone. */
    private static final Map<Module, Animation> ANIMATIONS = new IdentityHashMap<>();

    private ClassicActiveMods() {}

    static void reset() {
        ANIMATIONS.clear();
    }

    /** {@code top} is the y of the first row's text, in framebuffer pixels. */
    static void render(final LegacyCanvas c, final ModuleArrayList list, final int top) {
        Outline outline = list.getOutline();
        Transition transition = list.getTransition();
        boolean smooth = list.isAnimated() && transition != Transition.SLIDE;
        boolean slide = list.isAnimated() && transition != Transition.SMOOTH;

        int right = c.width() - 2;
        int y = top;
        int previousWidth = -7;
        float hue = (System.nanoTime() / 1_000_000L % 2000L) / 2000.0F;
        // What the bottom cap is drawn in when nothing is listed: never seen, but it was the old default.
        int color = 0xFF00C0FF;
        int rowHeight = Math.round(c.textHeight(NAME_FACE, NAME_SIZE)) + ROW_TRIM;

        for (Row row : ordered(c, list)) {
            Module module = row.module();
            Animation animation = animation(module);
            animation.changeDirection(module.isEnabled() ? Animation.Direction.BACKWARDS : Animation.Direction.FORWARDS);
            float hidden = animation.calcPercent();
            if (!module.isEnabled() && (hidden == 1.0F || !list.isAnimated())) {
                continue;
            }

            color = java.awt.Color.HSBtoRGB(hue, 1.0F, 1.0F);
            String mode = row.mode();
            int width = row.width();
            float shown = list.isAnimated() ? 1.0F - Easing.easeOutQuad(hidden, 0.0F, 1.0F, 1.0F) : 1.0F;
            int height = smooth ? (int) (rowHeight * shown) : rowHeight;

            c.push();
            try {
                if (slide) {
                    c.translate(width * (1.0F - shown), 0.0F);
                }
                if (outline == Outline.RIGHT) {
                    c.translate(-3.0F, 0.0F);
                }

                c.fill(right - width - 3, y + 1, right + 2, y + height + 1, BOX);
                switch (outline) {
                    case RIGHT -> c.fill(right + 2, y + 1, right + 7, y + 1 + height, color);
                    case LEFT -> c.fill(right - width - 6, y + 1, right - width - 3, y + 1 + height, color);
                    case ALL -> {
                        c.fill(right - width - 5, y + 1, right - width - 3, y + 1 + height, color);
                        // The cap along the top runs out to where the box above ends, so the edge is one line.
                        c.fill(right - width - 3, y + 1, right - previousWidth - 5, y + 3, color);
                    }
                    case NONE -> {
                    }
                }

                // Clipped to the box, so a row that is still growing shows only the part of its text that has come out.
                c.scissor(right - width - 6, y + 1, right + 8, y + height + 1);
                c.text(NAME_FACE, NAME_SIZE, module.getName(), right - width, y, color);
                if (!mode.isEmpty()) {
                    c.text(NAME_FACE, MODE_SIZE, mode, right - c.textWidth(NAME_FACE, MODE_SIZE, mode), y + 1.6F, MODE_COLOR);
                }
                c.unscissor();
            } finally {
                c.pop();
            }

            y += height;
            previousWidth = width;
            hue += HUE_STEP;
            if (hue > 1.0F) {
                hue = 0.0F;
            }
        }

        if (outline == Outline.ALL && previousWidth > 0) {
            c.fill(right - previousWidth - 5, y + 1, right + 2, y + 3, color);
        }
    }

    private static Animation animation(final Module module) {
        return ANIMATIONS.computeIfAbsent(module, m -> {
            Animation created = new Animation(200, 200, m.isEnabled() ? Animation.Direction.BACKWARDS : Animation.Direction.FORWARDS);
            created.setProgress(m.isEnabled() ? 0.0F : 1.0F);
            return created;
        });
    }

    /** The mode after a module's name, with the space that separates them, or empty when it has none. */
    private static String modeOf(final Module module) {
        String mode = ModuleArrayList.modeOf(module);
        return mode == null ? "" : " " + mode;
    }

    /** A module with what it is drawn as this frame: its mode and how wide its box's text is. */
    private record Row(Module module, String mode, int width) {}

    /** Widest box first; boxes as wide as each other go by name. */
    private static List<Row> ordered(final LegacyCanvas c, final ModuleArrayList list) {
        List<Row> rows = new ArrayList<>();
        for (Module module : Client.getInstance().getModuleManager().all()) {
            if (list.lists(module)) {
                String mode = modeOf(module);
                int width = (int) (c.textWidth(NAME_FACE, NAME_SIZE, module.getName()) + c.textWidth(NAME_FACE, MODE_SIZE, mode));
                rows.add(new Row(module, mode, width));
            }
        }

        rows.sort((a, b) -> {
            int byWidth = Integer.compare(b.width(), a.width());
            return byWidth != 0 ? byWidth : a.module().getName().compareTo(b.module().getName());
        });
        return rows;
    }
}
