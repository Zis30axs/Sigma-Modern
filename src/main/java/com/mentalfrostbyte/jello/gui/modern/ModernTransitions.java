package com.mentalfrostbyte.jello.gui.modern;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Cross-screen fade. A SigmaModern screen that fades itself out before navigating calls
 * {@link #fadeInNextScreen()}; the next screen - even a vanilla one - then fades in from the same night-sky
 * color instead of appearing abruptly. The fade's clock starts on that screen's first rendered frame, so a
 * slow first frame (loading a world list, say) doesn't eat the animation.
 */
public final class ModernTransitions {
    private static final float DURATION = 0.34F;
    private static final int COLOR = 0x050F1A;
    private static boolean pending;
    private static long start;

    private ModernTransitions() {}

    static void fadeInNextScreen() {
        pending = true;
        start = 0L;
    }

    /** Called once per rendered screen frame from {@code Gui.extractRenderState}, after the screen itself. */
    public static void render(GuiGraphicsExtractor g) {
        if (pending) {
            pending = false;
            start = System.nanoTime();
        }
        if (start == 0L) return;
        float t = (System.nanoTime() - start) / 1_000_000_000F / DURATION;
        if (t >= 1F) {
            start = 0L;
            return;
        }
        int alpha = Math.round(255 * (1F - ModernStyle.easeOut(t)));
        g.nextStratum();
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), alpha << 24 | COLOR);
    }
}
