package com.mentalfrostbyte.jello.module.impl.combat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectileCadenceTest {
    @Test
    void acquisitionAndBurstDelayAreIndependentOfWithinBurstFrequency() {
        ProjectileCadence clock = new ProjectileCadence();
        assertFalse(clock.ready(100, 500));
        assertFalse(clock.ready(599, 500));
        assertTrue(clock.ready(600, 500));
        clock.fired(600, 2, 4, 500);
        assertFalse(clock.ready(849, 500));
        assertTrue(clock.ready(850, 500));
        clock.fired(850, 2, 4, 500);
        assertFalse(clock.ready(1349, 500));
        assertTrue(clock.ready(1350, 500));
    }

    @Test
    void pauseResetsPartialBurstAndRequiresFreshAcquisition() {
        ProjectileCadence clock = new ProjectileCadence();
        assertTrue(clock.ready(0, 0));
        clock.fired(0, 2, 20, 1000);
        clock.reset();
        assertFalse(clock.ready(100, 1000));
        assertTrue(clock.ready(1100, 1000));
        clock.fired(1100, 2, 20, 1000);
        assertTrue(clock.ready(1150, 1000));
    }

    @Test
    void lateTicksNeverCreateCatchUpThrows() {
        ProjectileCadence clock = new ProjectileCadence();
        assertTrue(clock.ready(0, 0));
        clock.fired(0, 16, 4, 0);
        assertTrue(clock.ready(10000, 0));
        clock.fired(10000, 16, 4, 0);
        assertFalse(clock.ready(10000, 0));
        assertFalse(clock.ready(10249, 0));
        assertTrue(clock.ready(10250, 0));
    }

    @Test
    void zeroBurstDelayStillRespectsRateLimitAndOneShotPerTick() {
        ProjectileCadence clock = new ProjectileCadence();
        assertTrue(clock.ready(0, 0));
        clock.fired(0, 1, 100, 0);
        assertFalse(clock.ready(49, 0));
        assertTrue(clock.ready(50, 0));
    }
}
