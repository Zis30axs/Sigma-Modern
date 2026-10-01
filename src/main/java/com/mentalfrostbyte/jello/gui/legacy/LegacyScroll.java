package com.mentalfrostbyte.jello.gui.legacy;

/**
 * Scroll state and bar for the old client's lists (the alt manager, the changelog, the ClickGUI panels).
 *
 * <p>Ported from {@code ScrollableContentPanel} / {@code VerticalScrollBar}: the wheel moves the content
 * {@code 35} px a notch, the bar fades in while the pointer is over the list or for half a second after a scroll
 * and fades out otherwise, the thumb is at least 20 px, clicking the track pages by a quarter of the content, and
 * the thumb can be dragged. Jello drew the bar as a soft dark capsule with feathered ends; Classic as a thin
 * flat rounded strip.</p>
 *
 * <p>All coordinates are in {@link LegacyCanvas} units. The list keeps its own content offset; the caller
 * draws its rows shifted up by {@link #offset()} inside a scissor.</p>
 */
public final class LegacyScroll {
    public static final int WHEEL_STEP = 35;
    private static final int MIN_THUMB = 20;
    private static final int TRACK_WIDTH = 11;

    public enum Style {
        JELLO, CLASSIC
    }

    private final Style style;
    private int offset;
    private float visibility;
    private long lastMoveNanos = Long.MIN_VALUE;
    private long lastDrawNanos;
    private boolean dragging;
    private float grab;

    public LegacyScroll(final Style style) {
        this.style = style;
    }

    public int offset() {
        return this.offset;
    }

    public void setOffset(final int offset) {
        this.offset = Math.max(0, offset);
    }

    public boolean dragging() {
        return this.dragging;
    }

    /** Keeps the offset inside the content. */
    public void clamp(final int contentHeight, final int viewHeight) {
        this.offset = Math.max(0, Math.min(this.offset, Math.max(0, contentHeight - viewHeight)));
    }

    /** A wheel notch ({@code delta} is {@code +1} for scrolling up). Returns whether the list can scroll at all. */
    public boolean wheel(final double delta, final int contentHeight, final int viewHeight) {
        if (contentHeight <= viewHeight) {
            return false;
        }
        this.offset -= (int) Math.round(WHEEL_STEP * delta);
        this.clamp(contentHeight, viewHeight);
        this.lastMoveNanos = System.nanoTime();
        return true;
    }

    /** The track's x for a list whose right edge is {@code listRight}: 16 px in, as the old bar sat. */
    public static int trackX(final int listRight) {
        return listRight - TRACK_WIDTH - 5;
    }

    private static float thumbHeight(final int trackH, final int contentH, final int viewH) {
        return Math.max(MIN_THUMB, Math.min(trackH, trackH * (viewH / (float) contentH)));
    }

    private float thumbTop(final int trackY, final int trackH, final int contentH, final int viewH) {
        float thumb = thumbHeight(trackH, contentH, viewH);
        float range = contentH - viewH;
        return trackY + (trackH - thumb) * (range <= 0 ? 0 : this.offset / range);
    }

    /** A press on the bar: starts a thumb drag, or pages by a quarter of the content. Returns whether it hit the bar. */
    public boolean press(
        final double mx, final double my, final int listRight, final int listTop, final int listHeight,
        final int contentH, final int viewH
    ) {
        if (contentH <= viewH) {
            return false;
        }
        int trackX = trackX(listRight);
        int trackY = listTop + 5;
        int trackH = listHeight - 10;
        if (mx < trackX || mx >= trackX + TRACK_WIDTH || my < trackY || my >= trackY + trackH) {
            return false;
        }
        float top = this.thumbTop(trackY, trackH, contentH, viewH);
        float thumb = thumbHeight(trackH, contentH, viewH);
        if (my >= top && my < top + thumb) {
            this.dragging = true;
            this.grab = (float) (my - top);
        } else {
            this.offset += (my < top ? -1 : 1) * (contentH / 4);
            this.clamp(contentH, viewH);
            this.lastMoveNanos = System.nanoTime();
        }
        return true;
    }

    public void drag(final double my, final int listTop, final int listHeight, final int contentH, final int viewH) {
        if (!this.dragging || contentH <= viewH) {
            return;
        }
        int trackY = listTop + 5;
        int trackH = listHeight - 10;
        float thumb = thumbHeight(trackH, contentH, viewH);
        float free = trackH - thumb;
        if (free <= 0) {
            return;
        }
        float fraction = (float) ((my - this.grab - trackY) / free);
        this.offset = Math.round(Math.max(0, Math.min(1, fraction)) * (contentH - viewH));
        this.lastMoveNanos = System.nanoTime();
    }

    public void release() {
        this.dragging = false;
    }

    /**
     * Draws the bar for a list occupying {@code (listLeft .. listRight, listTop .. listTop + listHeight)}.
     * {@code hovered} is whether the pointer is over the list.
     */
    public void draw(
        final LegacyCanvas c, final int listRight, final int listTop, final int listHeight,
        final int contentH, final int viewH, final boolean hovered, final float alpha
    ) {
        long now = System.nanoTime();
        float dt = this.lastDrawNanos == 0 ? 0 : Math.min(0.1F, (now - this.lastDrawNanos) / 1.0E9F);
        this.lastDrawNanos = now;
        boolean recent = this.lastMoveNanos != Long.MIN_VALUE && (now - this.lastMoveNanos) / 1_000_000L < 500;
        boolean show = contentH > viewH && (hovered || this.dragging || recent);
        // The old bar stepped 0.05 per frame at 60 fps: 3 per second either way.
        this.visibility = Math.max(0.0F, Math.min(1.0F, this.visibility + (show ? 3.0F : -3.0F) * dt));
        if (contentH <= viewH || this.visibility <= 0.0F) {
            return;
        }

        float fade = alpha * this.visibility;
        int trackX = trackX(listRight);
        int trackY = listTop;
        int trackH = listHeight - 10;
        float thumb = thumbHeight(trackH, contentH, viewH);
        float thumbY = this.thumbTop(listTop + 5, trackH, contentH, viewH);
        float thumbFade = fade * (this.dragging ? 0.75F : hovered ? 0.7F : 0.3F);
        int dark = 0xFF010101;
        int grey = 0xFF999999;

        if (this.style == Style.JELLO) {
            int cap = 5;
            int white = 0xFFFEFEFE;
            c.image(LegacyTexture.SCROLLBAR_TOP, trackX, trackY + 5, TRACK_WIDTH, cap, LegacyCanvas.alpha(white, 0.45F * fade));
            c.image(LegacyTexture.SCROLLBAR_BOTTOM, trackX, trackY + 5 + trackH - cap, TRACK_WIDTH, cap, LegacyCanvas.alpha(white, 0.45F * fade));
            c.fill(trackX, trackY + 5 + cap, trackX + TRACK_WIDTH, trackY + 5 + trackH - cap, LegacyCanvas.alpha(dark, 0.2F * fade));
            c.image(LegacyTexture.SCROLLBAR_TOP, trackX, thumbY, TRACK_WIDTH, cap, LegacyCanvas.alpha(white, thumbFade));
            c.image(LegacyTexture.SCROLLBAR_BOTTOM, trackX, thumbY + thumb - cap, TRACK_WIDTH, cap, LegacyCanvas.alpha(white, thumbFade));
            c.fill(trackX, Math.round(thumbY + cap), trackX + TRACK_WIDTH, Math.round(thumbY + thumb - cap),
                LegacyCanvas.alpha(dark, 0.45F * thumbFade));
        } else {
            int x = trackX + 8;
            int w = TRACK_WIDTH - 8;
            c.rounded(x, trackY + 5, w, trackH, w / 2, LegacyCanvas.alpha(grey, 0.1F * fade));
            c.rounded(x, Math.round(thumbY), w, Math.round(thumb), w / 2, LegacyCanvas.alpha(grey, thumbFade));
        }
    }
}
