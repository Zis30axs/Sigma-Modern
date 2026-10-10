package com.mentalfrostbyte.jello.module.impl.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EspTest {
    private static Esp.Candidate visible(double squaredDistance) {
        return new Esp.Candidate(false, true, true, false, false, false, squaredDistance);
    }

    @Test
    void rangeUsesSquaredMetresAndIncludesItsExactBoundary() {
        assertTrue(Esp.accepts(visible(64 * 64), 64F, false));
        assertFalse(Esp.accepts(visible(Math.nextUp(64.0 * 64.0)), 64F, false));
        assertTrue(Esp.accepts(visible(0), 64F, false));
        for (double invalid : new double[]{-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertFalse(Esp.accepts(visible(invalid), 64F, false));
        }
        for (float invalid : new float[]{0, -1, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            assertFalse(Esp.accepts(visible(10), invalid, false));
        }
    }

    @Test
    void invisiblesRequireAnExplicitOptionButNormalPlayersDoNot() {
        Esp.Candidate invisible = new Esp.Candidate(false, true, true, false, false, true, 100);
        assertFalse(Esp.accepts(invisible, 64F, false));
        assertTrue(Esp.accepts(invisible, 64F, true));
        assertTrue(Esp.accepts(visible(100), 64F, false));
    }

    @Test
    void visibilityOptionsCannotReviveSelfStaleDeadOrSpectatingPlayers() {
        Esp.Candidate[] excluded = {
                new Esp.Candidate(true, true, true, false, false, false, 100),
                new Esp.Candidate(false, false, true, false, false, false, 100),
                new Esp.Candidate(false, true, false, false, false, false, 100),
                new Esp.Candidate(false, true, true, true, false, false, 100),
                new Esp.Candidate(false, true, true, false, true, false, 100)
        };
        for (Esp.Candidate player : excluded) {
            assertFalse(Esp.accepts(player, 256F, true));
        }
    }
}
