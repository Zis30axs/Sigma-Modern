package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RwrThreatsTest {
    private static final AABB PLAYER = new AABB(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
    private static final RwrThreats.Obstruction CLEAR = (from, to) -> Double.POSITIVE_INFINITY;

    @Test void playerAimMustActuallyIntersectTheBodyFromEveryDirection() {
        Vec3 center = PLAYER.getCenter();
        for (Vec3 eye : List.of(new Vec3(0, 0.9, 8), new Vec3(0, 0.9, -8),
                new Vec3(8, 0.9, 0), new Vec3(-8, 0.9, 0), new Vec3(0, 8, 0))) {
            Vec3 towards = center.subtract(eye);
            assertTrue(RwrThreats.aimsAt(eye, towards, PLAYER, 10, CLEAR));
            assertFalse(RwrThreats.aimsAt(eye, towards.scale(-1), PLAYER, 10, CLEAR));
        }
        assertFalse(RwrThreats.aimsAt(new Vec3(2, 0.9, 8), new Vec3(0, 0, -1), PLAYER, 10, CLEAR));
        assertFalse(RwrThreats.aimsAt(new Vec3(0, 0.9, 8), new Vec3(0, 0, -1), PLAYER, 6, CLEAR));
    }

    @Test void aimWallBeforeOrAtTheBodyBlocksButOneBehindItDoesNot() {
        Vec3 eye = new Vec3(0, 0.9, 5);
        Vec3 direction = new Vec3(0, 0, -1);
        assertFalse(RwrThreats.aimsAt(eye, direction, PLAYER, 10, (from, to) -> 0.2));
        assertFalse(RwrThreats.aimsAt(eye, direction, PLAYER, 10, (from, to) -> 0.47));
        assertTrue(RwrThreats.aimsAt(eye, direction, PLAYER, 10, (from, to) -> 0.8));
    }

    @Test void approachReportsFractionalImpactTimeAndRejectsPassingOrRecedingArrows() {
        assertEquals(4.7, impact(new Vec3(0, 0.9, 5), new Vec3(0, 0, -1), CLEAR), 1.0E-6);
        assertEquals(Double.POSITIVE_INFINITY, impact(new Vec3(0, 0.9, 5), new Vec3(0, 0, 1), CLEAR));
        assertEquals(Double.POSITIVE_INFINITY, impact(new Vec3(1, 0.9, 5), new Vec3(0, 0, -1), CLEAR));
        assertEquals(Double.POSITIVE_INFINITY, impact(new Vec3(0, 0.9, 5), Vec3.ZERO, CLEAR));
    }

    @Test void arrowsAreDetectedAboveBelowAndOnEitherSide() {
        assertEquals(4.2, impact(new Vec3(0, 6, 0), new Vec3(0, -1, 0), CLEAR), 1.0E-6);
        assertEquals(5.0, impact(new Vec3(0, -5, 0), new Vec3(0, 1, 0), CLEAR), 1.0E-6);
        assertEquals(4.7, impact(new Vec3(5, 0.9, 0), new Vec3(-1, 0, 0), CLEAR), 1.0E-6);
        assertEquals(4.7, impact(new Vec3(-5, 0.9, 0), new Vec3(1, 0, 0), CLEAR), 1.0E-6);
    }

    @Test void gravityCreatesAThreatWhenItsStraightLineWouldPassAboveThePlayer() {
        Vec3 position = new Vec3(0, 3, 5);
        Vec3 velocity = new Vec3(0, 0, -1);
        assertEquals(Double.POSITIVE_INFINITY, impact(position, velocity, CLEAR));
        assertEquals(4.7, RwrThreats.impactTicks(position, velocity, PLAYER, Vec3.ZERO, 0.2, 1, 40, CLEAR), 1.0E-6);
    }

    @Test void gravityAndDragApplyAfterTheFirstArrowMovement() {
        assertEquals(0.7, RwrThreats.impactTicks(new Vec3(0, 1, 1), new Vec3(0, 0, -1),
                PLAYER, Vec3.ZERO, 2, 0.5, 40, CLEAR), 1.0E-6);
        assertTrue(RwrThreats.impactTicks(new Vec3(0, 0.9, 5), new Vec3(0, 0, -1),
                PLAYER, Vec3.ZERO, 0, 0.99, 40, CLEAR) > 4.7);
    }

    @Test void targetMotionCanAvoidAnExistingTrajectoryOrMoveIntoOne() {
        Vec3 velocity = new Vec3(0, 0, -1);
        assertEquals(Double.POSITIVE_INFINITY, RwrThreats.impactTicks(new Vec3(0, 0.9, 5), velocity,
                PLAYER, new Vec3(0.5, 0, 0), 0, 1, 40, CLEAR));
        assertEquals(4.7, RwrThreats.impactTicks(new Vec3(2, 0.9, 5), velocity,
                PLAYER, new Vec3(0.4, 0, 0), 0, 1, 40, CLEAR), 1.0E-6);
    }

    @Test void obstacleInAnyEarlierSegmentSuppressesTheWarning() {
        assertEquals(Double.POSITIVE_INFINITY, impact(new Vec3(0, 0.9, 5), new Vec3(0, 0, -1),
                (from, to) -> from.z == 4 ? 0.5 : Double.POSITIVE_INFINITY));
    }

    @Test void obstacleInTheImpactSegmentIsComparedWithThePlayersHitTime() {
        Vec3 from = new Vec3(0, 0.9, 5);
        Vec3 velocity = new Vec3(0, 0, -1);
        assertEquals(Double.POSITIVE_INFINITY, impact(from, velocity,
                (start, end) -> start.z == 1 ? 0.5 : Double.POSITIVE_INFINITY));
        assertEquals(4.7, impact(from, velocity,
                (start, end) -> start.z == 1 ? 0.9 : Double.POSITIVE_INFINITY), 1.0E-6);
    }

    @Test void nonThreateningPathsDoNotPerformWorldRaycasts() {
        AtomicInteger calls = new AtomicInteger();
        double time = impact(new Vec3(2, 0.9, 5), new Vec3(0, 0, -1), (from, to) -> {
            calls.incrementAndGet();
            return Double.POSITIVE_INFINITY;
        });
        assertEquals(Double.POSITIVE_INFINITY, time);
        assertEquals(0, calls.get());
    }

    @Test void invalidInputsAndPathsBeyondThePredictionWindowProduceNoWarning() {
        Vec3 position = new Vec3(0, 0.9, 5);
        Vec3 velocity = new Vec3(0, 0, -1);
        assertEquals(Double.POSITIVE_INFINITY, RwrThreats.impactTicks(position, velocity, PLAYER, Vec3.ZERO, 0, 1, 4, CLEAR));
        assertEquals(Double.POSITIVE_INFINITY, RwrThreats.impactTicks(position, velocity, PLAYER, Vec3.ZERO, 0, 1, 41, CLEAR));
        assertEquals(Double.POSITIVE_INFINITY, RwrThreats.impactTicks(position, velocity, PLAYER, Vec3.ZERO, Double.NaN, 1, 40, CLEAR));
        assertEquals(Double.POSITIVE_INFINITY, impact(new Vec3(Double.NaN, 0.9, 5), velocity, CLEAR));
        assertEquals(Double.POSITIVE_INFINITY, impact(position, new Vec3(0, 0, Double.NEGATIVE_INFINITY), CLEAR));
        assertEquals(Double.POSITIVE_INFINITY, impact(position, velocity, (from, to) -> Double.NaN));
        assertFalse(RwrThreats.aimsAt(position, Vec3.ZERO, PLAYER, 10, CLEAR));
        assertFalse(RwrThreats.aimsAt(position, velocity, PLAYER, Double.POSITIVE_INFINITY, CLEAR));
        assertFalse(RwrThreats.aimsAt(position, new Vec3(Double.NaN, 0, 0), PLAYER, 10, CLEAR));
    }

    @Test void urgentProjectilesCannotBeCrowdedOutByCloserPassiveContacts() {
        List<RwrThreats.Threat> input = new ArrayList<>();
        for (int id = 0; id < 20; id++) input.add(new RwrThreats.Threat(id, new Vec3(id, 0, 0), id,
                RwrThreats.Type.CONTACT, Double.POSITIVE_INFINITY));
        input.add(new RwrThreats.Threat(22, new Vec3(30, 0, 0), 30, RwrThreats.Type.ARROW, 0.5));
        input.add(new RwrThreats.Threat(23, new Vec3(50, 0, 0), 50, RwrThreats.Type.TRIDENT, 0.2));
        input.add(new RwrThreats.Threat(21, new Vec3(20, 0, 0), 20, RwrThreats.Type.AIM, Double.POSITIVE_INFINITY));
        List<RwrThreats.Threat> ordered = RwrThreats.ordered(input);
        assertEquals(RwrThreats.MAX_THREATS, ordered.size());
        assertEquals(List.of(23, 22, 21, 0), ordered.subList(0, 4).stream().map(RwrThreats.Threat::entityId).toList());
        assertThrows(UnsupportedOperationException.class, () -> ordered.add(input.getFirst()));
    }

    private static double impact(Vec3 position, Vec3 velocity, RwrThreats.Obstruction obstruction) {
        return RwrThreats.impactTicks(position, velocity, PLAYER, Vec3.ZERO, 0, 1, 40, obstruction);
    }
}
