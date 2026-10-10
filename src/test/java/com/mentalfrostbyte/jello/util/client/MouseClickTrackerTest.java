package com.mentalfrostbyte.jello.util.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MouseClickTrackerTest {
    @Test
    void theRollingSecondExcludesClicksExactlyAtItsOldestBoundary() {
        MouseClickTracker.Counter counter = new MouseClickTracker.Counter();
        Object world = new Object();
        click(counter, world, 0, 0L);
        click(counter, world, 0, 250_000_000L);
        click(counter, world, 0, 750_000_000L);
        assertEquals(3, counter.cps(0, world, true, 999_999_999L));
        assertEquals(2, counter.cps(0, world, true, 1_000_000_000L));
        assertEquals(1, counter.cps(0, world, true, 1_250_000_000L));
        assertEquals(0, counter.cps(0, world, true, 1_750_000_000L));
    }

    @Test
    void bothButtonsCountIndependentlyAndReleasesNeverAddClicks() {
        MouseClickTracker.Counter counter = new MouseClickTracker.Counter();
        Object world = new Object();
        counter.button(0, 1, world, true, 0L);
        counter.button(1, 1, world, true, 10L);
        counter.button(0, 1, world, true, 20L);
        counter.button(0, 0, world, true, 30L);
        assertEquals(1, counter.cps(0, world, true, 30L));
        counter.button(0, 1, world, true, 40L);
        counter.button(1, 1, world, true, 50L);
        counter.button(1, 0, world, true, 60L);
        assertEquals(2, counter.cps(0, world, true, 60L));
        assertEquals(1, counter.cps(1, world, true, 60L));
        click(counter, world, 2, 70L);
        assertEquals(2, counter.cps(0, world, true, 70L));
        assertEquals(1, counter.cps(1, world, true, 70L));
        assertEquals(0, counter.cps(2, world, true, 70L));
    }

    @Test
    void heldButtonsDoNotBecomeNewClicksWhenTheirOldClickExpires() {
        MouseClickTracker.Counter counter = new MouseClickTracker.Counter();
        Object world = new Object();
        counter.button(0, 1, world, true, 0L);
        counter.button(0, 2, world, true, 100L);
        assertEquals(1, counter.cps(0, world, true, 200L));
        assertEquals(0, counter.cps(0, world, true, 2_000_000_000L));
        counter.button(0, 1, world, true, 2_000_000_001L);
        assertEquals(0, counter.cps(0, world, true, 2_000_000_002L), "duplicate press still belongs to the held button");
        counter.button(0, 0, world, true, 2_000_000_003L);
        counter.button(0, 1, world, true, 2_000_000_004L);
        assertEquals(1, counter.cps(0, world, true, 2_000_000_005L));
    }

    @Test
    void menusEditorsAndFocusLossClearBothSidesAndIgnoreTheirClicks() {
        MouseClickTracker.Counter counter = new MouseClickTracker.Counter();
        Object world = new Object();
        click(counter, world, 0, 0L);
        click(counter, world, 1, 1L);
        counter.sync(world, false, 2L);
        counter.button(0, 1, world, false, 3L);
        counter.button(1, 1, world, false, 4L);
        assertEquals(0, counter.cps(0, world, false, 5L));
        assertEquals(0, counter.cps(1, world, false, 5L));
        assertEquals(0, counter.cps(0, world, true, 6L), "returning to the game must not revive pre-menu clicks");
        assertEquals(0, counter.cps(1, world, true, 6L));
        click(counter, world, 0, 7L);
        assertEquals(1, counter.cps(0, world, true, 8L));
    }

    @Test
    void worldChangesDisconnectAndExplicitEditorResetDropAllHistory() {
        MouseClickTracker.Counter counter = new MouseClickTracker.Counter();
        Object first = new Object();
        Object second = new Object();
        click(counter, first, 0, 0L);
        assertEquals(0, counter.cps(0, second, true, 1L));
        click(counter, second, 1, 2L);
        assertEquals(0, counter.cps(1, null, true, 3L), "a missing world is never gameplay even if a caller says it is");
        assertEquals(0, counter.cps(1, second, true, 4L));
        counter.button(0, 1, second, true, 5L);
        counter.reset();
        assertEquals(0, counter.cps(0, second, true, 6L));
        counter.button(0, 1, second, true, 7L);
        assertEquals(1, counter.cps(0, second, true, 8L), "reset also clears a stale held-button latch");
    }

    @Test
    void extremeInputBurstsUseBoundedStorageAndRetainTheNewestClicks() {
        MouseClickTracker.Counter counter = new MouseClickTracker.Counter();
        Object world = new Object();
        int total = MouseClickTracker.CAPACITY + 44;
        for (int click = 0; click < total; click++) click(counter, world, 0, click * 1_000L);
        assertEquals(MouseClickTracker.CAPACITY, counter.cps(0, world, true, total * 1_000L));
        assertEquals(0, counter.cps(1, world, true, total * 1_000L));
        assertEquals(MouseClickTracker.CAPACITY,
                counter.cps(0, world, true, MouseClickTracker.WINDOW_NANOS + 43_999L));
        assertEquals(MouseClickTracker.CAPACITY - 1,
                counter.cps(0, world, true, MouseClickTracker.WINDOW_NANOS + 44_000L));
        assertEquals(0, counter.cps(0, world, true, MouseClickTracker.WINDOW_NANOS + total * 1_000L));
    }

    @Test
    void aClockRollbackStartsANewEpochButSignedWrapKeepsNearbyClicks() {
        MouseClickTracker.Counter counter = new MouseClickTracker.Counter();
        Object world = new Object();
        click(counter, world, 0, 100L);
        click(counter, world, 1, 101L);
        assertEquals(0, counter.cps(0, world, true, 99L));
        assertEquals(0, counter.cps(1, world, true, 99L));
        click(counter, world, 0, 100L);
        assertEquals(1, counter.cps(0, world, true, 100L));

        counter.reset();
        long beforeWrap = Long.MAX_VALUE - 500_000_000L;
        click(counter, world, 0, beforeWrap);
        assertEquals(1, counter.cps(0, world, true, beforeWrap + 999_999_999L));
        assertEquals(0, counter.cps(0, world, true, beforeWrap + 1_000_000_000L));
    }

    @Test
    void queuedMenuAndOldWorldEventsCannotBecomeGameplayClicksLater() {
        MouseClickTracker.Counter counter = new MouseClickTracker.Counter();
        Object oldWorld = new Object();
        Object currentWorld = new Object();
        MouseClickTracker.InputContext queuedMenu = new MouseClickTracker.InputContext(currentWorld, false);
        MouseClickTracker.InputContext queuedOldWorld = new MouseClickTracker.InputContext(oldWorld, true);
        counter.button(0, 1, queuedMenu, currentWorld, true, 0L);
        counter.button(1, 1, queuedOldWorld, currentWorld, true, 1L);
        assertEquals(0, counter.cps(0, currentWorld, true, 2L));
        assertEquals(0, counter.cps(1, currentWorld, true, 2L));

        counter.button(0, 1, currentWorld, true, 3L);
        counter.button(0, 0, queuedMenu, currentWorld, true, 4L);
        counter.button(0, 1, currentWorld, true, 5L);
        assertEquals(1, counter.cps(0, currentWorld, true, 6L),
                "a stale menu release cannot release a current physical press or erase its click");
    }

    private static void click(final MouseClickTracker.Counter counter, final Object world, final int button, final long now) {
        counter.button(button, 1, world, true, now);
        counter.button(button, 0, world, true, now);
    }
}
