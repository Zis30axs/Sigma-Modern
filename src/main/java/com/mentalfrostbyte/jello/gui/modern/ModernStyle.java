package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shared ice-glass palette and drawing primitives.
 *
 * <p>Two surface families exist: a pale frosted workspace card ({@link #INK}/{@link #INK_MUTED} text over
 * {@link #PANEL_SURFACE}) used by the ClickGUI, and a darker glass surface ({@link #TEXT}/{@link #MUTED}
 * text) used by the main menu, window chrome and in-game HUD decorations. The ClickGUI's palette leans
 * toward the same cold ice-blue identity the main menu and its {@code card.png} sculpture already
 * establish ({@link #ICE}/{@link #GLOW}), rather than the neutral workspace grays the original
 * {@code Jello-Modern.html} reference used - a settings panel is more recognizably "Sigma" carrying that
 * identity than matching a demo page's own arbitrary color choices. Both surfaces rely on the caller having
 * already blurred the frame behind them (see {@link GuiVisuals#blurBackground}) - the panel itself only
 * draws the translucent tint, border and shadow that make the already-blurred world read as glass. All
 * drawing stays on the backend-neutral GUI pipeline.</p>
 */
public final class ModernStyle {
    // Dark-glass surface (main menu, window frame, in-game HUD decorations).
    public static final int TEXT = 0xFFEAF6FF;
    public static final int MUTED = 0xFFA2BFD1;
    public static final int ACCENT = 0xFF9DDEFF;

    // Pale ice-glass workspace-card surface (ClickGUI).
    public static final int INK = 0xFF15303E;
    public static final int INK_MUTED = 0xFF57798D;
    public static final int BLUE = 0xFF2E9BD6;
    public static final int ICE = 0xFFC3EFFF;
    public static final int GLOW = 0xFF7FE3FF;
    public static final int PANEL_SURFACE = 0xE6E8F3FA;
    public static final int PANEL_BORDER = 0xD6E9F8FF;

    private ModernStyle() {}

    // Render-thread only: GUI extraction never runs concurrently.
    private static float groupAlpha = 1F;

    /**
     * Fades everything drawn through Modern's primitives ({@link #rounded}, {@link #fill}, text, icons,
     * snow) until the scope closes; nested scopes multiply. This is how whole groups - a card, a menu row,
     * the dock - fade in and out without every draw call taking an alpha parameter.
     */
    public static AlphaScope alphaScope(float alpha) {
        return new AlphaScope(alpha);
    }

    public static final class AlphaScope implements AutoCloseable {
        private final float previous;

        private AlphaScope(float alpha) {
            this.previous = groupAlpha;
            groupAlpha = this.previous * Math.max(0F, Math.min(1F, alpha));
        }

        @Override public void close() {
            groupAlpha = this.previous;
        }
    }

    /** Applies the current {@link #alphaScope} to an ARGB color. */
    public static int a(int argb) {
        if (groupAlpha >= 1F) return argb;
        return Math.round((argb >>> 24) * groupAlpha) << 24 | (argb & 0x00FFFFFF);
    }

    public static float groupAlpha() {
        return groupAlpha;
    }

    public static void fill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int c = a(color);
        if ((c >>> 24) != 0) g.fill(x0, y0, x1, y1, c);
    }

    public static void fillGradient(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int top, int bottom) {
        g.fillGradient(x0, y0, x1, y1, a(top), a(bottom));
    }

    /** Standard ease-out used by every entrance: fast start, gentle settle. */
    public static float easeOut(float t) {
        t = Math.max(0F, Math.min(1F, t));
        float inv = 1F - t;
        return 1F - inv * inv * inv;
    }

    public static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        ModernShapeRenderer.rounded(g, x, y, w, h, radius, color);
    }

    /**
     * A vertical gradient over rows {@code y0..y1} of a rounded shape, corners included - unlike a plain
     * {@link #fillGradient} inset past the corners, which leaves an untinted strip down each side. The straight
     * middle takes the gradient itself; in the corner rows the shape is drawn clipped to those rows, in the
     * gradient's color at their middle (a corner is a dozen pixels tall, too short for the difference to show).
     */
    public static void roundedGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int y0, int y1, int top, int bottom) {
        if (y1 <= y0) return;
        // The same clamp ModernShapeRenderer applies, so the corner rows here are exactly its arcs.
        int r = Math.clamp(radius, 0, Math.min(32, Math.min(w, h) / 2));
        int from = Math.max(y0, y), to = Math.min(y1, y + h);
        int bodyTop = Math.max(from, y + r), bodyBottom = Math.min(to, y + h - r);
        if (bodyBottom > bodyTop) {
            fillGradient(g, x, bodyTop, x + w, bodyBottom, mix(top, bottom, (bodyTop - y0) / (float)(y1 - y0)),
                mix(top, bottom, (bodyBottom - y0) / (float)(y1 - y0)));
        }
        roundedRows(g, x, y, w, h, r, from, Math.min(to, y + r), y0, y1, top, bottom);
        roundedRows(g, x, y, w, h, r, Math.max(from, y + h - r), to, y0, y1, top, bottom);
    }

    private static void roundedRows(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int a, int b, int y0, int y1, int top, int bottom) {
        if (b <= a) return;
        g.enableScissor(x, a, x + w, b);
        try {
            rounded(g, x, y, w, h, r, mix(top, bottom, ((a + b) / 2F - y0) / (y1 - y0)));
        } finally {
            g.disableScissor();
        }
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Exponential smoothing shared by every hover/press/expand animation in Modern. */
    public static float smooth(float current, float target, float dtSeconds, float speed) {
        return current + (target - current) * (1 - (float)Math.exp(-dtSeconds * speed));
    }

    /**
     * Inserts a space between every character, the same wide-tracking trick the main menu's "M O D E R N"
     * eyebrow already uses - {@link ModernFontRenderer} has no real letter-spacing control.
     */
    public static String spaced(String text) {
        StringBuilder out = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            if (i > 0) out.append(' ');
            out.append(text.charAt(i));
        }
        return out.toString();
    }

    public static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int aa = a >>> 24, ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
            | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    /**
     * Draws a pale frosted workspace card: outer shadow, blurred-glass tint, border and a soft top
     * highlight sheen. The caller must have already called {@link GuiVisuals#blurBackground} this frame.
     */
    /**
     * A soft drop shadow that follows the shape's rounded corners - expanding, fading rounded rings -
     * unlike {@link GuiVisuals#softShadow}, whose square rings show as a boxy halo on light backgrounds.
     */
    public static void dropShadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, float strength) {
        int[] spread = {14, 9, 5, 2};
        int[] alpha = {9, 15, 22, 30};
        for (int i = 0; i < spread.length; i++) {
            int e = spread[i];
            int a = Math.round(alpha[i] * strength);
            if (a > 0) rounded(g, x - e, y - e + e / 2, w + e * 2, h + e * 2, Math.min(32, radius + e), a << 24 | 0x02070D);
        }
    }

    public static void glassCard(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius) {
        dropShadow(g, x, y + 5, w, h, radius, 1.1F);
        rounded(g, x, y, w, h, radius, PANEL_BORDER);
        rounded(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), PANEL_SURFACE);
        int sheenHeight = Math.max(1, h / 3);
        // Inset past the rounded corners so the gradient's own square top edge never pokes outside them.
        fillGradient(g, x + radius, y + 1, x + w - radius, y + 1 + sheenHeight, 0x30FFFFFF, 0x00FFFFFF);
    }

    /** Darker glass surface used by the in-game HUD's dynamic island and hotbar. */
    public static void darkGlass(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int surface) {
        dropShadow(g, x, y + 3, w, h, radius, 0.8F);
        rounded(g, x, y, w, h, radius, 0x4CFFFFFF);
        rounded(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), surface);
    }

    /**
     * A soft-edged halo behind a rounded shape, standing in for the reference's glow box-shadows. Built
     * from expanding {@link #rounded} passes on the ordinary textured pipeline rather than
     * {@link GuiVisuals#softGlow}'s additive-highlight pipeline, which - inside an active GUI scissor -
     * was found to wash out and discard the shape drawn right after it instead of blending normally.
     */
    public static void halo(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int rgb, float strength) {
        if (strength <= 0) return;
        int[] expand = {6, 4, 2};
        int[] alpha = {8, 16, 26};
        for (int i = 0; i < expand.length; i++) {
            int e = expand[i];
            int a = Math.round(alpha[i] * strength);
            if (a <= 0) continue;
            rounded(g, x - e, y - e, w + e * 2, h + e * 2, Math.min(32, radius + e), a << 24 | (rgb & 0x00FFFFFF));
        }
    }

    /**
     * An iOS-style pill toggle. {@code on} is an animated 0..1 fraction, not a boolean, so callers can
     * ease the knob and track color across frames the same way the reference's CSS transitions do.
     */
    public static void toggle(GuiGraphicsExtractor g, int x, int y, int w, int h, float on) {
        int off = 0x471C394A;
        int lit = BLUE;
        if (on > 0.02F) halo(g, x, y, w, h, h / 2, GLOW, on * 0.5F);
        rounded(g, x, y, w, h, h / 2, mix(off, lit, on));
        int knob = h - 4;
        int knobX = x + 2 + Math.round((w - h) * on);
        rounded(g, knobX, y + 2, knob, knob, knob / 2, 0xFFFFFFFF);
    }

    /** Small glowing status dot, used for "enabled" counters and the dynamic island's activity light. */
    public static void statusDot(GuiGraphicsExtractor g, int x, int y, int size, int color, boolean lit) {
        if (lit) halo(g, x, y, size, size, size / 2, color, 0.5F);
        rounded(g, x, y, size, size, size / 2, lit ? color : 0x807B96A8);
    }
}
