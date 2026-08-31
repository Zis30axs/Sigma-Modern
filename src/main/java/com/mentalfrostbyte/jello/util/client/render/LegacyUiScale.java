package com.mentalfrostbyte.jello.util.client.render;

import net.minecraft.client.Minecraft;

/**
 * Converts pixel measurements from Sigma's old custom GUI into modern Minecraft GUI coordinates.
 *
 * <p>The historical Jello/Classic screen base used the raw framebuffer dimensions returned by
 * {@code MainWindow#getWidth()/getHeight()}. Minecraft 26.2 {@code Screen.width/height}, however,
 * are already divided by the configured GUI scale. Reusing an old 455px measurement as 455 modern
 * GUI units therefore makes it twice as large at GUI scale 2, three times as large at scale 3, etc.
 * Keep old layout constants in their original framebuffer-pixel units and convert them at the edge.</p>
 */
public final class LegacyUiScale {

    private LegacyUiScale() {
    }

    public static float factor() {
        return 1.0F / Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    /** Converts a signed historical pixel coordinate or offset to modern logical GUI units. */
    public static int px(final int legacyPixels) {
        return Math.round(legacyPixels * factor());
    }

    /** Converts a historical positive size while keeping non-zero dimensions drawable. */
    public static int size(final int legacyPixels) {
        if (legacyPixels <= 0) {
            return 0;
        }
        return Math.max(1, px(legacyPixels));
    }

    /** Float variant for animation amplitudes and other sub-pixel values. */
    public static float px(final float legacyPixels) {
        return legacyPixels * factor();
    }
}
