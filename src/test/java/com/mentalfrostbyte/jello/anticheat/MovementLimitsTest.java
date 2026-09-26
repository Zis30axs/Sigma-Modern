package com.mentalfrostbyte.jello.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.anticheat.predict.MovementLimits;
import java.util.List;
import org.junit.jupiter.api.Test;

class MovementLimitsTest {

    private static final double SPRINT = 0.13;
    private static final double WALK = 0.1;

    @Test
    void sprintingOnGrassMatchesTheKnownFiveAndAHalfBlocksPerSecond() {
        // Vanilla sprint speed is 5.612 blocks/s with forward input only; the ceiling assumes diagonal input,
        // which is a little stronger.
        double perTick = MovementLimits.maxAveragePerTick(SPRINT, 0.6, MovementLimits.NORMAL_INPUT, true, 0.42, false);
        assertTrue(perTick * 20.0 > 5.6 && perTick * 20.0 < 6.0, "walking ceiling was " + perTick * 20.0 + " blocks/s");
    }

    @Test
    void hoppingRaisesTheCeilingAndStaysBelowThreeBlocksPerTick() {
        double walking = MovementLimits.maxAveragePerTick(SPRINT, 0.6, MovementLimits.NORMAL_INPUT, true, 0.42, false);
        double hopping = MovementLimits.maxAveragePerTick(SPRINT, 0.6, MovementLimits.NORMAL_INPUT, true, 0.42, true);
        assertTrue(hopping > walking, "hopping " + hopping + " should beat walking " + walking);
        assertTrue(hopping > 0.33 && hopping < 0.45, "sprint-hop ceiling was " + hopping);
    }

    @Test
    void notSeenInTheAirMeansTheWalkingCeiling() {
        double walking = MovementLimits.maxAveragePerTick(SPRINT, 0.6, MovementLimits.NORMAL_INPUT, true, 0.42, false);
        double hopping = MovementLimits.maxAveragePerTick(SPRINT, 0.6, MovementLimits.NORMAL_INPUT, true, 0.42, true);
        assertTrue(walking < hopping);
    }

    @Test
    void usingAnItemSlowsThePlayerToAFractionOfWalking() {
        double free = MovementLimits.maxAveragePerTick(WALK, 0.6, MovementLimits.NORMAL_INPUT, false, 0.42, false);
        double using = MovementLimits.maxAveragePerTick(WALK, 0.6, MovementLimits.USING_ITEM_INPUT, false, 0.42, false);
        assertTrue(using < free * 0.4, "using an item " + using + " vs free " + free);
    }

    @Test
    void slipperyGroundAcceleratesMoreSlowlyButNotByMuch() {
        // Ice trades acceleration for slide: the steady walking speed lands within a few percent of grass, which
        // is why the ceiling for a mixed window is the larger of the extremes and not a guess at the worst.
        double grass = MovementLimits.maxAveragePerTick(SPRINT, 0.6, MovementLimits.NORMAL_INPUT, true, 0.42, false);
        double ice = MovementLimits.maxAveragePerTick(SPRINT, 0.98, MovementLimits.NORMAL_INPUT, true, 0.42, false);
        assertTrue(Math.abs(ice - grass) < grass * 0.1, "ice " + ice + " vs grass " + grass);
    }

    @Test
    void aMixedSurfaceCeilingIsAtLeastEachSurfacesOwn() {
        double grass = MovementLimits.maxAveragePerTick(SPRINT, 0.6, MovementLimits.NORMAL_INPUT, true, 0.42, true);
        double ice = MovementLimits.maxAveragePerTick(SPRINT, 0.98, MovementLimits.NORMAL_INPUT, true, 0.42, true);
        double mixed = MovementLimits.maxAveragePerTick(SPRINT, 0.6, 0.98, MovementLimits.NORMAL_INPUT, true, 0.42, true);
        assertTrue(mixed >= grass && mixed >= ice);
    }

    @Test
    void anOrdinaryJumpTopsOutAtAboutOnePointTwoFiveBlocks() {
        assertEquals(1.2522, MovementLimits.jumpApex(0.42), 0.005);
    }

    @Test
    void theCeilingIsAtLeastWhatARealSprintHopperCovers() {
        // Independent simulation of a sprint-hopper: their average speed must sit under the ceiling.
        List<Harness.Point> points = Harness.run(480, true, true, SPRINT);
        double travelled = points.get(479).x() - points.get(239).x();
        double average = travelled / 240.0;
        double ceiling = MovementLimits.maxAveragePerTick(SPRINT, 0.6, MovementLimits.NORMAL_INPUT, true, 0.42, true);
        assertTrue(average <= ceiling, "real " + average + " vs ceiling " + ceiling);
        // ... and not by so much that the ceiling stops meaning anything.
        assertTrue(ceiling < average * 1.1, "real " + average + " vs ceiling " + ceiling);
    }
}
