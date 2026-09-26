package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModernLyricScrollTest {
    private static final float ROOM = 100F, OVERFLOW = 60F;

    @Test
    void aWordTimedLineWaitsUntilTheSweepReachesTheAnchorThenFollowsIt() {
        float anchor = ROOM * ModernLyricScroll.ANCHOR;
        assertEquals(0F, ModernLyricScroll.follow(0F, ROOM, OVERFLOW));
        assertEquals(0F, ModernLyricScroll.follow(anchor, ROOM, OVERFLOW));
        assertEquals(25F, ModernLyricScroll.follow(anchor + 25F, ROOM, OVERFLOW), 1e-4F);
        assertEquals(OVERFLOW, ModernLyricScroll.follow(ROOM + OVERFLOW, ROOM, OVERFLOW), "the end rests at the right edge");
    }

    @Test
    void aLineThatFitsNeverScrolls() {
        assertEquals(0F, ModernLyricScroll.follow(90F, ROOM, 0F));
        assertEquals(0F, ModernLyricScroll.timed(1_000L, 5_000L, 3_000L, 0F));
    }

    @Test
    void aLineTimedLinePausesAtTheStartThenCrossesAndRestsAtTheEnd() {
        // A 4 s slot from 1 s: still for its first 0.8 s, at the end from 3.4 s in (4.4 s) on.
        assertEquals(0F, ModernLyricScroll.timed(1_000L, 5_000L, 1_000L, OVERFLOW));
        assertEquals(0F, ModernLyricScroll.timed(1_000L, 5_000L, 1_800L, OVERFLOW));
        assertEquals(OVERFLOW, ModernLyricScroll.timed(1_000L, 5_000L, 4_400L, OVERFLOW), 1e-4F);
        assertEquals(OVERFLOW, ModernLyricScroll.timed(1_000L, 5_000L, 9_000L, OVERFLOW), 1e-4F);

        float last = 0F;
        for (long t = 1_000L; t <= 5_000L; t += 50L) {
            float offset = ModernLyricScroll.timed(1_000L, 5_000L, t, OVERFLOW);
            assertTrue(offset >= last, "never goes back");
            last = offset;
        }
    }

    @Test
    void aShortSlotStillReachesTheEndInTime() {
        assertEquals(OVERFLOW, ModernLyricScroll.timed(0L, 1_500L, 1_300L, OVERFLOW), 1e-4F);
    }

    @Test
    void aLineBeforeALongBreakFinishesAtTheSlowestSpeedInsteadOfCrawling() {
        long done = ModernLyricScroll.HOLD_START_MS + (long)Math.ceil(OVERFLOW / ModernLyricScroll.MIN_SPEED * 1000F);
        assertEquals(OVERFLOW, ModernLyricScroll.timed(0L, 30_000L, done, OVERFLOW), 1e-4F);
        assertTrue(ModernLyricScroll.timed(0L, 30_000L, done / 2, OVERFLOW) > 0F);
    }
}
