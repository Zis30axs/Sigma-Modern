package com.mentalfrostbyte.jello.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.check.FlightCheck;
import com.mentalfrostbyte.jello.anticheat.check.JesusCheck;
import com.mentalfrostbyte.jello.anticheat.check.RotationCheck;
import com.mentalfrostbyte.jello.anticheat.check.SneakCheck;
import com.mentalfrostbyte.jello.anticheat.check.SprintCheck;
import com.mentalfrostbyte.jello.anticheat.check.StepCheck;
import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import org.junit.jupiter.api.Test;

class ExtendedChecksTest {

    private static final double SPRINT = 0.13;
    private static final CheckSettings EAGER = new CheckSettings(true, true, true, true, true, true, true, true, true,
            1.15, 20, 2, 1.0, 1.0 / 4096.0);

    // --- FlightCheck refinements (AirJump & Glide) ---

    @Test
    void midAirDoubleJumpDuringFallIsFlaggedByFlight() {
        Harness harness = new Harness().withSettings(EAGER);
        harness.probe.floorY = 0.0;
        harness.report(Harness.START, 0.0, 20.0, true);
        // Fall from y=20 down to y=15, then jump back up to y=17 in mid-air.
        harness.report(Harness.START + 2 * Harness.TICK, 0.0, 18.5, false);
        harness.report(Harness.START + 4 * Harness.TICK, 0.0, 16.5, false);
        harness.report(Harness.START + 6 * Harness.TICK, 0.0, 15.0, false);
        harness.report(Harness.START + 8 * Harness.TICK, 0.0, 16.2, false);
        assertTrue(harness.alerted(FlightCheck.NAME), "mid-air jump during fall must flag Flight");
    }

    @Test
    void slowFallGlidingWithoutPotionParticlesIsFlaggedByFlight() {
        Harness harness = new Harness().withSettings(EAGER);
        harness.probe.floorY = 0.0;
        harness.report(Harness.START, 0.0, 40.0, true);
        // Descend steadily at 1.6 blocks/sec (0.08 blocks/tick) for 4 seconds without particles.
        for (int tick = 2; tick <= 80; tick += 2) {
            double y = 40.0 - tick * 0.08;
            harness.report(Harness.START + tick * Harness.TICK, 0.0, y, false);
        }
        assertTrue(harness.alerted(FlightCheck.NAME), "constant slow glide must flag Flight");
    }

    // --- StepCheck ---

    @Test
    void walkingUpStairsIsNotFlaggedByStep() {
        Harness harness = new Harness().withSettings(EAGER);
        for (int i = 0; i < 20; i++) {
            double y = i * 0.5;
            double x = i * 0.55;
            harness.probe.floorY = y;
            harness.report(Harness.START + i * 2 * Harness.TICK, x, y, true);
        }
        assertFalse(harness.alerted(StepCheck.NAME), harness.alerts.toString());
    }

    @Test
    void highStepTwoBlocksWhileGroundedIsFlagged() {
        Harness harness = new Harness().withSettings(EAGER);
        harness.probe.floorY = 0.0;
        harness.report(Harness.START, 0.0, 0.0, true);
        harness.probe.floorY = 2.0;
        harness.report(Harness.START + 2 * Harness.TICK, 0.5, 2.0, true);
        assertTrue(harness.alerted(StepCheck.NAME), "2-block grounded step must flag Step");
    }

    @Test
    void oneBlockWallStepWithNoHorizontalTravelIsFlagged() {
        Harness harness = new Harness().withSettings(EAGER);
        harness.probe.floorY = 0.0;
        harness.report(Harness.START, 0.0, 0.0, true);
        harness.probe.floorY = 1.0;
        harness.report(Harness.START + 2 * Harness.TICK, 0.1, 1.0, true);
        assertTrue(harness.alerted(StepCheck.NAME), "1-block vertical step with 0.1 horizontal move must flag Step");
    }

    @Test
    void raisedStepHeightAttributeAllowsHigherStep() {
        Harness harness = new Harness().withSettings(EAGER);
        harness.probe.floorY = 0.0;
        harness.report(Harness.START, 0.0, 0.0, 0.0, true, 0, 0.1, 0F, 0F, 2.0, 0.3);
        harness.probe.floorY = 1.5;
        harness.report(Harness.START + 2 * Harness.TICK, 0.1, 1.5, 0.0, true, 0, 0.1, 0F, 0F, 2.0, 0.3);
        assertFalse(harness.alerted(StepCheck.NAME), harness.alerts.toString());
    }

    // --- SneakCheck ---

    @Test
    void legalSneakingIsNeverFlagged() {
        Harness harness = new Harness().withSettings(EAGER);
        Harness.LegalPlayer player = new Harness.LegalPlayer();
        for (int tick = 0; tick < 200; tick++) {
            player.tick(false, false, 0.1, 0.3, 0.42);
            if (tick % 2 == 0) {
                harness.report(Harness.START + tick * Harness.TICK, player.x, 0.0, 0.0, true,
                        PlayerFlags.SNEAKING, 0.1, 0F, 0F, 0.6, 0.3);
            }
        }
        assertFalse(harness.alerted(SneakCheck.NAME), harness.alerts.toString());
        assertEquals(0.0, harness.level(SneakCheck.NAME), 1.0E-9);
    }

    @Test
    void swiftSneakThreeIsAllowedWhenAttributeMatches() {
        Harness harness = new Harness().withSettings(EAGER);
        Harness.LegalPlayer player = new Harness.LegalPlayer();
        for (int tick = 0; tick < 200; tick++) {
            player.tick(false, false, 0.1, 0.75, 0.42);
            if (tick % 2 == 0) {
                harness.report(Harness.START + tick * Harness.TICK, player.x, 0.0, 0.0, true,
                        PlayerFlags.SNEAKING, 0.1, 0F, 0F, 0.6, 0.75);
            }
        }
        assertFalse(harness.alerted(SneakCheck.NAME), harness.alerts.toString());
    }

    @Test
    void fastSneakAtFullWalkingSpeedIsFlagged() {
        Harness harness = new Harness().withSettings(EAGER);
        Harness.LegalPlayer player = new Harness.LegalPlayer();
        for (int tick = 0; tick < 120; tick++) {
            player.tick(false, false, 0.1, 1.0, 0.42);
            if (tick % 2 == 0) {
                harness.report(Harness.START + tick * Harness.TICK, player.x, 0.0, 0.0, true,
                        PlayerFlags.SNEAKING, 0.1, 0F, 0F, 0.6, 0.3);
            }
        }
        assertTrue(harness.alerted(SneakCheck.NAME), "walking at full speed while sneaking must flag Sneak");
    }

    // --- SprintCheck ---

    @Test
    void sprintingWhileUsingItemIsFlagged() {
        Harness harness = new Harness().withSettings(EAGER);
        for (int tick = 0; tick <= 40; tick += 2) {
            harness.report(Harness.START + tick * Harness.TICK, tick * 0.2, 0.0, 0.0, true,
                    PlayerFlags.SPRINTING | PlayerFlags.USING_ITEM, SPRINT, -90F, 0F, 0.6, 0.3);
        }
        assertTrue(harness.alerted(SprintCheck.NAME), "sprinting while using item must flag Sprint");
    }

    @Test
    void backwardsOmniSprintIsFlaggedWhileForwardSprintIsClean() {
        // Yaw = -90 looks along +X (since lookX = -sin(-90) = +1, lookZ = 0).
        // Moving along +X with yaw = +90 (looking -X) is sprinting straight backwards.
        Harness backwards = new Harness().withSettings(EAGER);
        Harness.LegalPlayer p1 = new Harness.LegalPlayer();
        for (int tick = 0; tick < 120; tick++) {
            p1.tick(true, false, SPRINT, 1.0, 0.42);
            if (tick % 2 == 0) {
                backwards.report(Harness.START + tick * Harness.TICK, p1.x, 0.0, 0.0, true,
                        PlayerFlags.SPRINTING, SPRINT, 90F, 0F, 0.6, 0.3);
            }
        }
        assertTrue(backwards.alerted(SprintCheck.NAME), "sprinting straight backwards must flag Sprint");

        Harness forward = new Harness().withSettings(EAGER);
        Harness.LegalPlayer p2 = new Harness.LegalPlayer();
        for (int tick = 0; tick < 120; tick++) {
            p2.tick(true, false, SPRINT, 1.0, 0.42);
            if (tick % 2 == 0) {
                forward.report(Harness.START + tick * Harness.TICK, p2.x, 0.0, 0.0, true,
                        PlayerFlags.SPRINTING, SPRINT, -90F, 0F, 0.6, 0.3);
            }
        }
        assertFalse(forward.alerted(SprintCheck.NAME), "forward sprinting must never flag Sprint");
    }

    // --- RotationCheck ---

    @Test
    void pitchOutsideNinetyDegreesIsFlagged() {
        Harness harness = new Harness().withSettings(EAGER);
        harness.report(Harness.START, 0.0, 0.0, 0.0, true, 0, Sample.DEFAULT_MOVE_SPEED, 0F, 89.5F, 0.6, 0.3);
        assertFalse(harness.alerted(RotationCheck.NAME));

        harness.report(Harness.START + 2 * Harness.TICK, 0.0, 0.0, 0.0, true, 0, Sample.DEFAULT_MOVE_SPEED, 0F, 120.0F, 0.6, 0.3);
        assertTrue(harness.alerted(RotationCheck.NAME), "pitch 120 must flag Rotation");
    }

    // --- JesusCheck ---

    @Test
    void walkingFlatAcrossWaterIsFlaggedByJesus() {
        Harness harness = new Harness().withSettings(EAGER);
        harness.probe.floorY = -10.0;
        harness.probe.environment = true;
        harness.probe.liquid = true;
        for (int tick = 0; tick <= 80; tick += 2) {
            harness.report(Harness.START + tick * Harness.TICK, tick * 0.22, 62.0, 0.0, false,
                    0, Sample.DEFAULT_MOVE_SPEED, -90F, 0F, 0.6, 0.3);
        }
        assertTrue(harness.alerted(JesusCheck.NAME), "flat walk across water must flag Jesus");
    }

    @Test
    void bobbingOrSwimmingInWaterIsNotFlaggedByJesus() {
        Harness harness = new Harness().withSettings(EAGER);
        harness.probe.floorY = -10.0;
        harness.probe.environment = true;
        harness.probe.liquid = true;
        for (int tick = 0; tick <= 80; tick += 2) {
            double bobY = 62.0 + 0.35 * Math.sin(tick * 0.4);
            harness.report(Harness.START + tick * Harness.TICK, tick * 0.15, bobY, 0.0, false,
                    0, Sample.DEFAULT_MOVE_SPEED, -90F, 0F, 0.6, 0.3);
        }
        assertFalse(harness.alerted(JesusCheck.NAME), "normal bobbing in water must not flag Jesus");
    }
}
