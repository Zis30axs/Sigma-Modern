package com.mentalfrostbyte.jello.module.impl.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PearlRescueCycleTest {
    @Test
    void onlyOnePearlCanBeThrownPerRescue() {
        PearlRescueCycle cycle = new PearlRescueCycle();
        assertFalse(cycle.shot(0, 10));
        assertTrue(cycle.begin(10, 100));
        assertFalse(cycle.shot(11, 10));
        cycle.planned();
        assertTrue(cycle.shot(12, 10));
        assertEquals(PearlRescueCycle.Stage.WAITING, cycle.stage());
        assertFalse(cycle.shot(13, 10));
        cycle.planned();
        assertEquals(PearlRescueCycle.Stage.WAITING, cycle.stage());
        assertFalse(cycle.begin(13, 200));
    }

    @Test
    void repeatedPlanningCannotExtendTheHoldBudgetOrResurrectAnExpiredAttempt() {
        PearlRescueCycle cycle = new PearlRescueCycle();
        cycle.begin(20, 30);
        cycle.planned();
        assertEquals(15, cycle.remainingTicks(35));
        cycle.planned();
        assertFalse(cycle.begin(49, 200));
        assertEquals(1, cycle.remainingTicks(49));
        assertTrue(cycle.expired(50));
        cycle.planned();
        assertFalse(cycle.shot(50, 5));
        assertEquals(0, cycle.remainingTicks(50));
        assertTrue(cycle.expired(500));
    }

    @Test
    void waitingAllowsRoundedUpFlightTimeAndTwentyTicksForServerResponse() {
        PearlRescueCycle cycle = new PearlRescueCycle();
        cycle.begin(100, 150);
        cycle.planned();
        assertTrue(cycle.shot(105, 12.1));
        assertEquals(33, cycle.remainingTicks(105));
        assertFalse(cycle.expired(137));
        assertTrue(cycle.expired(138));
    }

    @Test
    void flightGraceNeverExceedsTheOriginalHardDeadline() {
        PearlRescueCycle cycle = new PearlRescueCycle();
        cycle.begin(10, 40);
        cycle.planned();
        assertTrue(cycle.shot(45, 30));
        assertEquals(5, cycle.remainingTicks(45));
        assertFalse(cycle.expired(49));
        assertTrue(cycle.expired(50));
    }

    @Test
    void failedPhysicsResultsCannotStartWaitingOrConsumeTheOneShot() {
        PearlRescueCycle cycle = new PearlRescueCycle();
        cycle.begin(0, 100);
        cycle.planned();
        for (double flight : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            assertFalse(cycle.shot(10, flight));
            assertEquals(PearlRescueCycle.Stage.AIMING, cycle.stage());
        }
        assertTrue(cycle.shot(10, 0));
        assertEquals(20, cycle.remainingTicks(10));
    }

    @Test
    void resetClearsWaitExpiryAndAllowsAnIndependentRescue() {
        PearlRescueCycle cycle = new PearlRescueCycle();
        cycle.begin(0, 100);
        cycle.planned();
        cycle.shot(10, 5);
        cycle.reset();
        assertEquals(PearlRescueCycle.Stage.IDLE, cycle.stage());
        assertEquals(0, cycle.remainingTicks(15));
        assertFalse(cycle.expired(1000));
        cycle.planned();
        assertEquals(PearlRescueCycle.Stage.IDLE, cycle.stage());
        assertTrue(cycle.begin(2000, 60));
        assertEquals(60, cycle.remainingTicks(2000));
        assertFalse(cycle.expired(2059));
        assertTrue(cycle.expired(2060));
    }

    @Test
    void holdDurationIsBoundedAndCounterOverflowDoesNotExpireImmediately() {
        PearlRescueCycle cycle = new PearlRescueCycle();
        cycle.begin(0, Integer.MAX_VALUE);
        assertEquals(200, cycle.remainingTicks(0));
        cycle.reset();
        cycle.begin(0, 0);
        assertFalse(cycle.expired(0));
        assertTrue(cycle.expired(1));
        cycle.reset();
        cycle.begin(Long.MAX_VALUE - 10, 200);
        assertEquals(10, cycle.remainingTicks(Long.MAX_VALUE - 10));
        assertFalse(cycle.expired(Long.MAX_VALUE - 1));
        assertTrue(cycle.expired(Long.MAX_VALUE));
    }
}
