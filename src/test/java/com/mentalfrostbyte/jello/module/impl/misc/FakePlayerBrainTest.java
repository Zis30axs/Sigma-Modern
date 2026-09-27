package com.mentalfrostbyte.jello.module.impl.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.impl.misc.FakePlayer.Mode;
import com.mentalfrostbyte.jello.util.math.Rotations;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FakePlayerBrainTest {

    private static final Vec3 ANCHOR = new Vec3(100.5, 64.0, -20.5);
    private static final FakePlayerBrain.Ground FLAT = (x, z) -> 64.0;
    private static final double RADIUS = 6.0;

    private static FakePlayerBrain brain(final long seed) {
        return new FakePlayerBrain(ANCHOR, seed);
    }

    @Test
    void everyModeStaysInsideItsCircle() {
        Vec3 farOpponent = ANCHOR.add(40.0, 0.0, 0.0);
        for (Mode mode : Mode.values()) {
            for (long seed = 0; seed < 5; seed++) {
                FakePlayerBrain brain = brain(seed);
                for (int tick = 0; tick < 2000; tick++) {
                    if (tick % 97 == 0) {
                        brain.knockback(tick * 13.0F); // even shoved about
                    }
                    brain.tick(mode, 2.0, RADIUS, farOpponent, FLAT);
                    double off = FakePlayerBrain.horizontal(ANCHOR, brain.position());
                    assertTrue(off <= RADIUS + 1.0E-9, mode + " seed " + seed + " tick " + tick + " is " + off + " out");
                    assertTrue(brain.position().y >= 64.0 - 1.0E-9, mode + " went below the ground");
                }
            }
        }
    }

    @Test
    void walkingIsAtAPlayersPaceOnTheGround() {
        FakePlayerBrain brain = brain(1);
        Vec3 last = brain.position();
        for (int tick = 0; tick < 400; tick++) {
            brain.tick(Mode.MOVING, 1.0, RADIUS, null, FLAT);
            assertEquals(64.0, brain.position().y, 1.0E-9, "never leaves the ground");
            assertTrue(FakePlayerBrain.horizontal(last, brain.position()) <= FakePlayerBrain.WALK + 1.0E-9, "tick " + tick);
            last = brain.position();
        }
    }

    @Test
    void jumpsFollowVanillasArc() {
        FakePlayerBrain brain = brain(2);
        double highest = 64.0;
        int landings = 0;
        boolean wasAirborne = false;
        for (int tick = 0; tick < 1000; tick++) {
            brain.tick(Mode.JUMPING, 1.0, RADIUS, null, FLAT);
            highest = Math.max(highest, brain.position().y);
            if (wasAirborne && brain.onGround()) {
                landings++;
            }
            wasAirborne = !brain.onGround();
        }
        assertTrue(landings > 20, "it jumps: " + landings);
        assertTrue(highest > 65.2 && highest < 65.26, "a vanilla jump tops out near 1.25 blocks: " + (highest - 64.0));
    }

    @Test
    void flyingStaysBetweenTheGroundAndItsCeiling() {
        FakePlayerBrain brain = brain(3);
        double lowest = Double.MAX_VALUE;
        double highest = 0.0;
        for (int tick = 0; tick < 2000; tick++) {
            brain.tick(Mode.FLYING, 1.0, RADIUS, null, FLAT);
            lowest = Math.min(lowest, brain.position().y);
            highest = Math.max(highest, brain.position().y);
        }
        assertTrue(highest > 67.0 && highest <= 70.0, "flies well up, no higher than the ceiling: " + highest);
        assertTrue(lowest >= 64.0, "and not through the floor: " + lowest);
    }

    @Test
    void combatKeepsAtReachFacingTheOpponentAndSwings() {
        FakePlayerBrain brain = brain(4);
        Vec3 opponent = ANCHOR.add(1.0, 0.0, 1.0);
        int swings = 0;
        double farthest = 0.0;
        for (int tick = 0; tick < 600; tick++) {
            brain.tick(Mode.COMBAT_SIMULATION, 1.0, RADIUS, opponent, FLAT);
            if (brain.swung()) {
                swings++;
            }
            if (tick > 60) {
                farthest = Math.max(farthest, FakePlayerBrain.horizontal(opponent, brain.position()));
                float towards = Rotations.towards(brain.position(), opponent).yaw();
                assertTrue(Math.abs(Mth.wrapDegrees(brain.yaw() - towards)) < 1.0F, "faces the opponent, tick " + tick);
            }
        }
        assertTrue(farthest < 4.5, "stays close: " + farthest);
        assertTrue(swings > 30, "swings about twice a second in reach: " + swings);
    }

    @Test
    void anOpponentOutsideTheCircleIsWatchedFromItsEdge() {
        FakePlayerBrain brain = brain(5);
        Vec3 opponent = ANCHOR.add(30.0, 0.0, 0.0);
        for (int tick = 0; tick < 300; tick++) {
            brain.tick(Mode.COMBAT_SIMULATION, 1.0, RADIUS, opponent, FLAT);
        }
        assertEquals(RADIUS, FakePlayerBrain.horizontal(ANCHOR, brain.position()), 0.5, "at the edge nearest the opponent");
        assertTrue(brain.position().x > ANCHOR.x + RADIUS - 1.0);
    }

    @Test
    void knockbackPushesAlongTheAttackersFacing() {
        FakePlayerBrain brain = brain(6);
        brain.knockback(0.0F); // attacker facing south, +z
        Vec3 before = brain.position();
        brain.tick(Mode.MOVING, 0.0, RADIUS, null, FLAT);
        assertTrue(brain.position().z - before.z > 0.3, "pushed south");
        assertTrue(brain.position().y > 64.0, "and off the ground");
    }
}
