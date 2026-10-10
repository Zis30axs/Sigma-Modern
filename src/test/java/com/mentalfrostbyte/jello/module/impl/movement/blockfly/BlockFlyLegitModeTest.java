package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockFlyLegitModeTest {
    @Test void longEagleDelayCannotOverrunTheFootSupportDeadline() {
        long now = 4_000_000_000L;
        assertEquals(now + 145_000_000L, BlockFlyLegitMode.eagleDeadline(now, 200, 5000));
        assertEquals(now, BlockFlyLegitMode.eagleDeadline(now, 30, 5000));
    }

    @Test void negativeEagleDelayMeansEarlyAndNeverBeforeTheCurrentTick() {
        long now = 4_000_000_000L;
        assertEquals(now + 100_000_000L, BlockFlyLegitMode.eagleDeadline(now, 200, -100));
        assertEquals(now, BlockFlyLegitMode.eagleDeadline(now, 200, -1000));
        assertEquals(now + 25_000_000L, BlockFlyLegitMode.eagleDeadline(now, 200, 25));
    }

    @Test void timerArithmeticSaturatesAndAimInterpolationStaysBounded() {
        assertEquals(Long.MAX_VALUE, BlockFlyLegitMode.safeAddNanos(Long.MAX_VALUE - 5, 10));
        assertEquals(0, BlockFlyLegitMode.millisToNanos(Double.NaN));
        assertEquals(0, BlockFlyLegitMode.smoothProgress(5, 10, 20));
        assertEquals(0.5, BlockFlyLegitMode.smoothProgress(15, 10, 20));
        assertEquals(1, BlockFlyLegitMode.smoothProgress(30, 10, 20));
    }
}
