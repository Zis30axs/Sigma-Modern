package com.mentalfrostbyte.jello.util.game.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Backend-neutral GUI effects for Sigma presentations.
 *
 * <p>The old client implemented blur with hand-managed OpenGL framebuffers and GL state. Minecraft 26.2
 * already owns a backend-neutral GUI blur boundary, so Sigma deliberately requests that path instead of
 * recreating an OpenGL-only framebuffer stack. Soft glow/shadow is built from the current GUI additive
 * pipeline and therefore remains usable by both OpenGL and Vulkan render backends.</p>
 */
public final class GuiVisuals {

    private GuiVisuals() {
    }

    /**
     * Blurs everything submitted before this call and leaves later GUI elements crisp.
     * Call at most once per rendered frame; the underlying GuiRenderState enforces that invariant.
     */
    public static void blurBackground(final GuiGraphicsExtractor graphics) {
        graphics.blurBeforeThisStratum();
    }

    /** Draws a cheap multi-ring additive glow around a rectangle. */
    public static void softGlow(
        final GuiGraphicsExtractor graphics,
        final int x,
        final int y,
        final int width,
        final int height,
        final int rgb,
        final int radius,
        final float strength
    ) {
        if (radius <= 0 || width <= 0 || height <= 0 || strength <= 0.0F) {
            return;
        }

        int rings = Math.min(8, Math.max(3, radius / 2));
        for (int i = rings; i >= 1; i--) {
            float t = i / (float) rings;
            int expand = Math.max(1, Math.round(radius * t));
            float falloff = 1.0F - t;
            int alpha = Math.min(255, Math.max(0, Math.round(255.0F * strength * (0.08F + falloff * falloff * 0.30F))));
            int color = alpha << 24 | rgb & 0x00FFFFFF;
            graphics.fill(
                RenderPipelines.GUI_TEXT_HIGHLIGHT,
                x - expand,
                y - expand,
                x + width + expand,
                y + height + expand,
                color
            );
        }
    }

    /** Draws a soft translucent shadow without relying on framebuffer post-processing. */
    public static void softShadow(
        final GuiGraphicsExtractor graphics,
        final int x,
        final int y,
        final int width,
        final int height,
        final int radius,
        final float strength
    ) {
        if (radius <= 0 || width <= 0 || height <= 0 || strength <= 0.0F) {
            return;
        }

        int rings = Math.min(10, Math.max(3, radius / 2));
        for (int i = rings; i >= 1; i--) {
            float t = i / (float) rings;
            int expand = Math.max(1, Math.round(radius * t));
            float falloff = 1.0F - t;
            int alpha = Math.min(160, Math.max(0, Math.round(160.0F * strength * (0.06F + falloff * falloff * 0.28F))));
            graphics.fill(x - expand, y - expand, x + width + expand, y + height + expand, alpha << 24);
        }
    }
}
