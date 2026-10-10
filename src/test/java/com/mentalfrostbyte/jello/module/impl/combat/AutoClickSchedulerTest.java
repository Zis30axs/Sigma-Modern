package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.impl.combat.AutoClickScheduler.AutoSettings;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class AutoClickSchedulerTest {
    private static final AutoSettings FIXED = new AutoSettings(8, 12, 0, 0, 250, 0);

    @Test
    void autoStartsAfterTheNativeClickAndZeroVariationUsesTheMidpointCps() {
        AutoClickScheduler scheduler = scheduler();
        assertFalse(scheduler.pollAuto(0, FIXED));
        scheduler.beginAuto(10, FIXED);
        assertFalse(scheduler.pollAuto(10, FIXED));
        assertFalse(scheduler.pollAuto(109, FIXED));
        assertTrue(scheduler.pollAuto(110, FIXED));
        assertFalse(scheduler.pollAuto(209, FIXED));
        assertTrue(scheduler.pollAuto(210, FIXED));
    }

    @Test
    void lateFramesEmitOneClickAndDropElapsedIntervals() {
        AutoClickScheduler scheduler = scheduler();
        scheduler.beginAuto(0, FIXED);
        assertTrue(scheduler.pollAuto(5000, FIXED));
        assertFalse(scheduler.pollAuto(5000, FIXED));
        assertFalse(scheduler.pollAuto(5001, FIXED));
        assertFalse(scheduler.pollAuto(5099, FIXED));
        assertTrue(scheduler.pollAuto(5100, FIXED));
    }

    @Test
    void consumingAnotherNativeClickRebasesTheNextSupplement() {
        AutoClickScheduler scheduler = scheduler();
        scheduler.beginAuto(0, FIXED);
        scheduler.beginAuto(95, FIXED);
        assertFalse(scheduler.pollAuto(100, FIXED));
        assertFalse(scheduler.pollAuto(194, FIXED));
        assertTrue(scheduler.pollAuto(195, FIXED));
    }

    @Test
    void twoQueuedNativeConsumesWithOnePhysicalMarkerArmExactlyOneHelperBurst() {
        AutoClickScheduler scheduler = scheduler();
        assertFalse(scheduler.consumedNativeClick(0, false, true, FIXED, 2, 35));
        assertTrue(scheduler.consumedNativeClick(10, true, true, FIXED, 2, 35));
        assertFalse(scheduler.pollHelper(44));
        assertTrue(scheduler.pollHelper(45));
        assertFalse(scheduler.pollHelper(79));
        assertTrue(scheduler.pollHelper(80));
        assertFalse(scheduler.pollHelper(115));
    }

    @Test
    void nativeKeyboardRepeatsCannotStarveAutoClicks() {
        AutoClickScheduler scheduler = scheduler();
        assertTrue(scheduler.consumedNativeClick(0, true, false, FIXED, 2, 35));
        int clicks = 0;
        for (long now = 10; now <= 1000; now += 10) {
            assertFalse(scheduler.consumedNativeClick(now, false, false, FIXED, 2, 35));
            if (scheduler.pollAuto(now, FIXED)) clicks++;
        }
        assertEquals(10, clicks);
    }

    @Test
    void nativeKeyboardRepeatsNeitherRebaseNorRefillHelperCredits() {
        AutoClickScheduler scheduler = scheduler();
        assertTrue(scheduler.consumedNativeClick(0, true, true, FIXED, 2, 35));
        int clicks = 0;
        for (long now = 5; now <= 500; now += 5) {
            assertFalse(scheduler.consumedNativeClick(now, false, true, FIXED, 2, 35));
            if (scheduler.pollHelper(now)) clicks++;
        }
        assertEquals(2, clicks);
    }

    @Test
    void changedSettingsAndExplicitResetDiscardPendingClicks() {
        AutoClickScheduler scheduler = scheduler();
        AutoSettings changed = new AutoSettings(10, 20, 0, 0, 250, 0);
        scheduler.beginAuto(0, FIXED);
        assertFalse(scheduler.pollAuto(100, changed));
        assertFalse(scheduler.pollAuto(200, FIXED), "old settings must not revive a previous schedule");
        scheduler.beginAuto(200, FIXED);
        scheduler.reset();
        assertFalse(scheduler.pollAuto(500, FIXED));
        scheduler.triggerHelper(500, 2, 35);
        scheduler.reset();
        assertFalse(scheduler.pollHelper(535));
    }

    @Test
    void allVariationStaysInsideTheConfiguredCpsInterval() {
        AutoSettings settings = new AutoSettings(20, 5, 5, 30, 50, 100);
        long[] intervals = intervals(new AutoClickScheduler(new Random(8349)), settings, 500);
        for (long interval : intervals) assertTrue(interval >= 50 && interval <= 200);
        assertTrue(java.util.Arrays.stream(intervals).distinct().count() > 30);
    }

    @Test
    void seedMakesTheWholePaceSequenceReproducible() {
        AutoSettings settings = new AutoSettings(6, 18, 3, 15, 175, 65);
        assertArrayEquals(intervals(new AutoClickScheduler(new Random(832)), settings, 100),
                intervals(new AutoClickScheduler(new Random(832)), settings, 100));
    }

    @Test
    void randomAndJitterIndependentlyChangePaceWithoutZitter() {
        AutoSettings randomOnly = new AutoSettings(8, 12, 0, 0, 250, 100);
        assertArrayEquals(new long[] {91, 91, 91}, intervals(fixedRandom(0.75), randomOnly, 3));
        AutoSettings jitterOnly = new AutoSettings(5, 20, 0, 20, 250, 0);
        assertArrayEquals(new long[] {90, 90, 90}, intervals(fixedRandom(0.75), jitterOnly, 3));
    }

    @Test
    void zitterPersistsAcrossClicksAndMsControlsWhenItChanges() {
        AutoSettings slow = new AutoSettings(5, 15, 4, 0, 250, 0);
        assertArrayEquals(new long[] {100, 100, 100, 94, 94, 94, 91},
                intervals(fixedRandom(0.75), slow, 7));
        AutoSettings fast = new AutoSettings(5, 15, 4, 0, 50, 0);
        assertArrayEquals(new long[] {100, 94, 91}, intervals(fixedRandom(0.75), fast, 3));
    }

    @Test
    void nonFiniteAndReversedSettingsRemainFiniteAndBounded() {
        AutoSettings invalid = new AutoSettings(Double.NaN, Double.POSITIVE_INFINITY,
                Double.NaN, Double.NEGATIVE_INFINITY, Long.MIN_VALUE, Double.POSITIVE_INFINITY);
        assertEquals(new AutoSettings(8, 12, 0, 0, 50, 0), invalid);
        assertArrayEquals(new long[] {100, 100}, intervals(scheduler(), invalid, 2));
        assertEquals(FIXED, new AutoSettings(12, 8, -5, -30, 250, -10));
    }

    @Test
    void equalCpsWithFractionalMillisecondsNeverExceedsTheMaximum() {
        AutoSettings settings = new AutoSettings(12, 12, 5, 30, 50, 100);
        assertArrayEquals(new long[] {84, 84, 84, 84}, intervals(scheduler(), settings, 4));
    }

    @Test
    void helperEmitsExactlyTheRequestedSupplementsAfterTheOriginalClick() {
        AutoClickScheduler scheduler = scheduler();
        scheduler.triggerHelper(0, 2, 35);
        assertFalse(scheduler.pollHelper(0));
        assertFalse(scheduler.pollHelper(34));
        assertTrue(scheduler.pollHelper(35));
        assertFalse(scheduler.pollHelper(35));
        assertFalse(scheduler.pollHelper(69));
        assertTrue(scheduler.pollHelper(70));
        assertFalse(scheduler.pollHelper(105));
    }

    @Test
    void aNewPhysicalClickReplacesTheUnfinishedHelperBurst() {
        AutoClickScheduler scheduler = scheduler();
        scheduler.triggerHelper(0, 5, 35);
        assertTrue(scheduler.pollHelper(35));
        scheduler.triggerHelper(40, 1, 35);
        assertFalse(scheduler.pollHelper(70));
        assertTrue(scheduler.pollHelper(75));
        assertFalse(scheduler.pollHelper(110));
    }

    @Test
    void helperDropsCatchUpBurstsAndExpiresStaleSupplements() {
        AutoClickScheduler scheduler = scheduler();
        scheduler.triggerHelper(0, 5, 35);
        assertTrue(scheduler.pollHelper(200));
        assertFalse(scheduler.pollHelper(200));
        assertFalse(scheduler.pollHelper(234));
        assertTrue(scheduler.pollHelper(235));
        assertFalse(scheduler.pollHelper(350));
        assertFalse(scheduler.pollHelper(385));
    }

    @Test
    void changingSchedulerModeClearsTheOtherModesPendingClick() {
        AutoClickScheduler scheduler = scheduler();
        scheduler.beginAuto(0, FIXED);
        scheduler.triggerHelper(10, 1, 35);
        assertTrue(scheduler.pollHelper(45));
        assertFalse(scheduler.pollAuto(100, FIXED));
        scheduler.triggerHelper(200, 5, 35);
        scheduler.beginAuto(210, FIXED);
        assertFalse(scheduler.pollHelper(245));
        assertTrue(scheduler.pollAuto(310, FIXED));
    }

    @Test
    void clockRollbackClearsBothModesWhileSignedWrapPreservesElapsedTime() {
        AutoClickScheduler scheduler = scheduler();
        scheduler.beginAuto(100, FIXED);
        assertFalse(scheduler.pollAuto(99, FIXED));
        assertFalse(scheduler.pollAuto(200, FIXED));
        scheduler.triggerHelper(300, 2, 35);
        assertFalse(scheduler.pollHelper(299));
        assertFalse(scheduler.pollHelper(335));
        long beforeWrap = Long.MAX_VALUE - 50;
        scheduler.beginAuto(beforeWrap, FIXED);
        assertFalse(scheduler.pollAuto(beforeWrap + 99, FIXED));
        assertTrue(scheduler.pollAuto(beforeWrap + 100, FIXED));
    }

    private static AutoClickScheduler scheduler() {
        return new AutoClickScheduler(new Random(123));
    }

    private static AutoClickScheduler fixedRandom(double value) {
        return new AutoClickScheduler(new RandomGenerator() {
            @Override public long nextLong() { return 0; }
            @Override public double nextDouble() { return value; }
        });
    }

    private static long[] intervals(AutoClickScheduler scheduler, AutoSettings settings, int count) {
        long[] intervals = new long[count];
        long now = 0;
        scheduler.beginAuto(now, settings);
        for (int index = 0; index < count; index++) {
            long previous = now;
            do {
                assertTrue(now - previous <= 1000, "a configured interval must make progress");
                now++;
            } while (!scheduler.pollAuto(now, settings));
            intervals[index] = now - previous;
        }
        return intervals;
    }
}
