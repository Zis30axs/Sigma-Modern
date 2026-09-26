package com.mentalfrostbyte.jello.gui.modern;

/**
 * How far to scroll a lyric line that is wider than the box it is shown in, so the whole line gets seen instead of
 * being cut short with an ellipsis. Both answers are offsets in pixels, from 0 (the line's start at the box's left
 * edge) to {@code overflow} (its end at the right edge).
 *
 * <p>Word-timed lyrics know where the singer is, so the line {@linkplain #follow follows the sweep}. Line-timed ones
 * only know when the line starts and when the next one does, so the line is {@linkplain #timed scrolled over that
 * time} - an estimate of the singing, with a pause at each end so the start and the end can be read.</p>
 */
final class ModernLyricScroll {
    /** Where in the box the sung edge is held while the line scrolls: far enough in that the next words show. */
    static final float ANCHOR = 0.6F;
    /** The pause before a line-timed line starts to move, at most. */
    static final long HOLD_START_MS = 1_000L;
    /** The pause at the end of a line-timed line before the next, at most. */
    static final long HOLD_END_MS = 800L;
    /** Slowest scroll, in px per second: a line followed by a long break finishes at this speed rather than crawling. */
    static final float MIN_SPEED = 18F;

    private ModernLyricScroll() {}

    /** Keeps the sweep's edge ({@code edge} px into the line) at {@link #ANCHOR} of a box {@code room} px wide. */
    static float follow(float edge, float room, float overflow) {
        if (overflow <= 0F) return 0F;
        return Math.max(0F, Math.min(overflow, edge - room * ANCHOR));
    }

    /**
     * Scrolls through the line's slot, {@code startMs} to {@code endMs}: still for the first fifth (at most
     * {@link #HOLD_START_MS}), then eased across to the end, arriving before the last sixth or so (at most
     * {@link #HOLD_END_MS}) - or sooner, at {@link #MIN_SPEED}, when the slot runs long.
     */
    static float timed(long startMs, long endMs, long positionMs, float overflow) {
        long slot = endMs - startMs;
        if (overflow <= 0F || slot <= 0L) return 0F;
        float holdStart = Math.min(HOLD_START_MS, slot * 0.2F);
        float holdEnd = Math.min(HOLD_END_MS, slot * 0.15F);
        float travel = Math.min(slot - holdStart - holdEnd, overflow / MIN_SPEED * 1000F);
        float t = Math.max(0F, Math.min(1F, (positionMs - startMs - holdStart) / Math.max(1F, travel)));
        return overflow * t * t * (3F - 2F * t);
    }
}
