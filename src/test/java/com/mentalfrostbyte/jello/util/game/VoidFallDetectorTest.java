package com.mentalfrostbyte.jello.util.game;

import static com.mentalfrostbyte.jello.util.game.VoidFallDetector.Support.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class VoidFallDetectorTest {
    @Test void sustainedMeasuredFallNeedsAllDefaultConditions() {
        var gate = grounded();
        for (int tick = 1; tick < 10; tick++) {
            assertFalse(gate.tick(fall(64 - tick * 0.5, VOID), VoidFallDetector.DEFAULTS).dangerous());
        }
        var result = gate.tick(fall(59, VOID), VoidFallDetector.DEFAULTS);
        assertTrue(result.dangerous());
        assertEquals(5, result.fallenDistance());
        assertEquals(10, result.airborneTicks());
        assertEquals(10, result.descendingTicks());
        assertEquals(new Vec3(0.5, 64, 0.5), result.lastSafePosition());
    }

    @Test void longFallOverTerrainDoesNotBecomeVoidAndUnknownCannotTrigger() {
        var gate = grounded();
        for (int tick = 1; tick <= 16; tick++) {
            var result = gate.tick(fall(64 - tick, tick <= 8 ? SUPPORTED : UNKNOWN), VoidFallDetector.DEFAULTS);
            assertFalse(result.dangerous());
        }
        assertFalse(gate.tick(fall(47, VOID), VoidFallDetector.DEFAULTS).dangerous());
        assertFalse(gate.tick(fall(46, VOID), VoidFallDetector.DEFAULTS).dangerous());
        assertTrue(gate.tick(fall(45, VOID), VoidFallDetector.DEFAULTS).dangerous());
    }

    @Test void spawnInMidairAndBriefGroundFlagDoNotArmRescue() {
        var gate = new VoidFallDetector.Gate();
        gate.tick(ground(64), VoidFallDetector.DEFAULTS);
        gate.tick(ground(64), VoidFallDetector.DEFAULTS);
        for (int tick = 1; tick <= 20; tick++) {
            var result = gate.tick(fall(64 - tick * 0.5, VOID), VoidFallDetector.DEFAULTS);
            assertFalse(result.dangerous());
            assertNull(result.lastSafePosition());
        }
    }

    @Test void shortDropAndUpwardMovementCannotAccumulateAcrossBounces() {
        var gate = grounded();
        for (int tick = 1; tick <= 10; tick++) {
            assertFalse(gate.tick(fall(64 - tick * 0.2, VOID), VoidFallDetector.DEFAULTS).dangerous());
        }
        var rising = gate.tick(new VoidFallDetector.Sample(new Vec3(0.5, 62.3, 0.5), 0.3, true, false, VOID),
                VoidFallDetector.DEFAULTS);
        assertEquals(0, rising.fallenDistance());
        assertEquals(0, rising.descendingTicks());
        for (int tick = 1; tick <= 5; tick++) {
            assertFalse(gate.tick(fall(62.3 - tick * 0.8, VOID), VoidFallDetector.DEFAULTS).dangerous());
        }
        assertTrue(gate.tick(fall(57.5, VOID), VoidFallDetector.DEFAULTS).dangerous());
    }

    @Test void velocityAloneIsNotEvidenceOfActuallyFalling() {
        var gate = grounded();
        for (int tick = 0; tick < 20; tick++) {
            var result = gate.tick(fall(64, VOID), VoidFallDetector.DEFAULTS);
            assertFalse(result.dangerous());
            assertEquals(0, result.fallenDistance());
        }
    }

    @Test void flightWaterRidingDeathOrOtherIneligibleStateClearsTheAnchor() {
        var gate = grounded();
        var ignored = gate.tick(new VoidFallDetector.Sample(new Vec3(0.5, 63, 0.5), -1, false, false, VOID),
                VoidFallDetector.DEFAULTS);
        assertNull(ignored.lastSafePosition());
        for (int tick = 1; tick <= 15; tick++) {
            assertFalse(gate.tick(fall(63 - tick * 0.5, VOID), VoidFallDetector.DEFAULTS).dangerous());
        }
    }

    @Test void teleportAndResetRequireNewStableFooting() {
        var gate = grounded();
        var corrected = gate.tick(fall(10, VOID), VoidFallDetector.DEFAULTS);
        assertFalse(corrected.dangerous());
        assertNull(corrected.lastSafePosition());
        for (int tick = 0; tick < 3; tick++) gate.tick(ground(10), VoidFallDetector.DEFAULTS);
        gate.reset();
        assertNull(gate.tick(fall(9, VOID), VoidFallDetector.DEFAULTS).lastSafePosition());
    }

    @Test void staleGroundAnchorExpiresAfterTenSecondsInAir() {
        var gate = grounded();
        for (int tick = 0; tick < 201; tick++) gate.tick(fall(64, UNKNOWN), VoidFallDetector.DEFAULTS);
        for (int tick = 1; tick <= 15; tick++) {
            assertFalse(gate.tick(fall(64 - tick * 0.5, VOID), VoidFallDetector.DEFAULTS).dangerous());
        }
    }

    @Test void safeHistoryIsBoundedImmutableAndNewestFirst() {
        var gate = grounded();
        VoidFallDetector.Result result = null;
        for (int x = 1; x <= 20; x++) {
            result = gate.tick(new VoidFallDetector.Sample(new Vec3(x, 64, 0.5), 0, true, true, SUPPORTED),
                    VoidFallDetector.DEFAULTS);
        }
        assertEquals(12, result.safePositions().size());
        assertEquals(20, result.lastSafePosition().x);
        List<Vec3> positions = result.safePositions();
        assertThrows(UnsupportedOperationException.class, () -> positions.add(Vec3.ZERO));
    }

    @Test void probeFindsDeepGroundBelowZeroAndNearFutureHorizontalLanding() {
        var world = new ProbeWorld(-64, 319);
        AABB player = new AABB(0.2, 40, 0.2, 0.8, 41.8, 0.8);
        world.support = new AABB(0, -50, 0, 1, -49, 1);
        assertEquals(SUPPORTED, VoidFallDetector.probe(world, player, Vec3.ZERO));
        world.support = new AABB(2, 10, 0, 3, 11, 1);
        assertEquals(VOID, VoidFallDetector.probe(world, player, Vec3.ZERO));
        assertEquals(SUPPORTED, VoidFallDetector.probe(world, player, new Vec3(0.4, -0.5, 0)));
        assertEquals(-64, world.lastVolume.minY);
        assertTrue(world.lastVolume.maxX > 3);
    }

    @Test void edgeSupportPreventsPrematureRescueAndBlocksAboveFeetDoNotCount() {
        var world = new ProbeWorld(-64, 319);
        AABB player = new AABB(1.05, 20, 0.2, 1.65, 21.8, 0.8);
        world.support = new AABB(0, 0, 0, 1, 1, 1);
        assertEquals(SUPPORTED, VoidFallDetector.probe(world, player, Vec3.ZERO));
        world.support = new AABB(1, 21, 0, 2, 22, 1);
        assertEquals(VOID, VoidFallDetector.probe(world, player, Vec3.ZERO));
    }

    @Test void unloadedChunkOrProbeBudgetExhaustionRemainsUnknownWithoutWorldReads() {
        var world = new ProbeWorld(-64, 319);
        world.missingX = 1;
        AABB player = new AABB(14.2, 40, 0.2, 14.8, 41.8, 0.8);
        assertEquals(UNKNOWN, VoidFallDetector.probe(world, player, new Vec3(0.4, -1, 0)));
        assertEquals(0, world.reads);
        world.missingX = Integer.MAX_VALUE;
        assertEquals(UNKNOWN, VoidFallDetector.probe(world,
                new AABB(0.2, 100000, 0.2, 0.8, 100001.8, 0.8), new Vec3(2, -1, 2)));
        assertEquals(0, world.reads);
    }

    @Test void invalidInputAndUnboundedPredictionCannotConfirmVoid() {
        var world = new ProbeWorld(-64, 319);
        AABB player = new AABB(0.2, 40, 0.2, 0.8, 41.8, 0.8);
        assertEquals(UNKNOWN, VoidFallDetector.probe(world, player, new Vec3(Double.NaN, -1, 0)));
        assertEquals(UNKNOWN, VoidFallDetector.probe(world, player, new Vec3(3, -1, 0)));
        assertEquals(UNKNOWN, VoidFallDetector.probe(world,
                new AABB(0, 0, 0, 100, 2, 100), Vec3.ZERO));
        assertEquals(0, world.reads);
        assertThrows(IllegalArgumentException.class, () -> new VoidFallDetector.Limits(Double.NaN, 10, 6));
        assertThrows(IllegalArgumentException.class, () -> new VoidFallDetector.Limits(4, 0, 6));
    }

    private static VoidFallDetector.Gate grounded() {
        var gate = new VoidFallDetector.Gate();
        for (int tick = 0; tick < 3; tick++) gate.tick(ground(64), VoidFallDetector.DEFAULTS);
        return gate;
    }

    private static VoidFallDetector.Sample ground(double y) {
        return new VoidFallDetector.Sample(new Vec3(0.5, y, 0.5), 0, true, true, SUPPORTED);
    }

    private static VoidFallDetector.Sample fall(double y, VoidFallDetector.Support support) {
        return new VoidFallDetector.Sample(new Vec3(0.5, y, 0.5), -0.5, true, false, support);
    }

    private static final class ProbeWorld implements VoidFallDetector.Environment {
        private final int minY;
        private final int maxY;
        private int missingX = Integer.MAX_VALUE;
        private int reads;
        private AABB support;
        private AABB lastVolume;

        ProbeWorld(int minY, int maxY) { this.minY = minY; this.maxY = maxY; }
        public int minY() { return this.minY; }
        public int maxY() { return this.maxY; }
        public boolean loaded(int chunkX, int chunkZ) { return chunkX != this.missingX; }
        public boolean catches(AABB volume) {
            this.reads++;
            this.lastVolume = volume;
            return this.support != null && this.support.intersects(volume);
        }
    }
}
