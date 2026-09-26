package com.mentalfrostbyte.jello.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.check.SpeedCheck;
import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpeedCheckTest {

    private static final double SPRINT = 0.13;
    private static final int SIXTY_SECONDS = 1200;

    /** A legal trajectory with every position scaled along x, as a speed cheat would do. */
    private static List<Harness.Point> scaled(final List<Harness.Point> points, final double factor) {
        return points.stream().map(p -> new Harness.Point(p.tick(), p.x() * factor, p.y(), p.onGround())).toList();
    }

    @Test
    void aLegalSprintHopperIsNeverFlagged() {
        Harness harness = new Harness();
        harness.feed(Harness.run(SIXTY_SECONDS, true, true, SPRINT), 2, PlayerFlags.SPRINTING, SPRINT);
        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
        assertEquals(0.0, harness.level(SpeedCheck.NAME), 1.0E-9);
    }

    @Test
    void aLegalWalkerIsNeverFlagged() {
        Harness harness = new Harness();
        harness.feed(Harness.run(SIXTY_SECONDS, false, false, 0.1), 2, 0, 0.1);
        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
    }

    @Test
    void aLegalHopperIsNeverFlaggedUnderNetworkJitter() {
        // Uneven reports (1-4 ticks each), late arrivals and stalls that end in a burst: the time base has to
        // absorb all of it. Several seeds, so one lucky shuffle cannot hide a flaw.
        for (long seed = 1; seed <= 12; seed++) {
            Harness harness = new Harness();
            harness.feedJittered(Harness.run(SIXTY_SECONDS, true, true, SPRINT), seed, PlayerFlags.SPRINTING, SPRINT);
            assertTrue(harness.alerts.isEmpty(), "seed " + seed + ": " + harness.alerts);
            assertEquals(0.0, harness.level(SpeedCheck.NAME), 1.0E-9, "seed " + seed);
        }
    }

    @Test
    void aLegalHopperOnTheOldProtocolsCoarsePositionsIsNeverFlagged() {
        // 1.8 rounds positions to 1/32 of a block.
        Harness harness = new Harness().withSettings(CheckSettings.balanced().withQuantStep(1.0 / 32.0));
        for (Harness.Point point : Harness.run(SIXTY_SECONDS, true, true, SPRINT)) {
            if (point.tick() % 2 == 0) {
                harness.report(Harness.START + point.tick() * Harness.TICK, Harness.quantize(point.x(), 1.0 / 32.0),
                        Harness.quantize(point.y(), 1.0 / 32.0), 0.0, point.onGround(), PlayerFlags.SPRINTING, SPRINT);
            }
        }

        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
    }

    @Test
    void aSpeedCheatIsFlagged() {
        Harness harness = new Harness();
        harness.feed(scaled(Harness.run(SIXTY_SECONDS, true, true, SPRINT), 2.4), 2, PlayerFlags.SPRINTING, SPRINT);
        assertTrue(harness.alerted(SpeedCheck.NAME), "a 2.4x sprint-hopper must be flagged");
    }

    @Test
    void aGroundSpeedCheatIsHeldToTheWalkingCeiling() {
        // Never leaves the ground, so the (lower) walking ceiling applies: 2x sprint speed is far past it.
        Harness harness = new Harness();
        harness.feed(scaled(Harness.run(SIXTY_SECONDS, true, false, SPRINT), 2.0), 2, PlayerFlags.SPRINTING, SPRINT);
        assertTrue(harness.alerted(SpeedCheck.NAME));
    }

    @Test
    void aSpeedPotionIsAllowedWhenTheAttributeShowsIt() {
        // Speed II raises the attribute to 0.1 * 1.4; sprinting multiplies again. The legal player is fast, and
        // the ceiling follows the attribute.
        double fast = 0.1 * 1.4 * 1.3;
        Harness harness = new Harness();
        harness.feed(Harness.run(SIXTY_SECONDS, true, true, fast), 2, PlayerFlags.SPRINTING, fast);
        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
    }

    @Test
    void theSameSpeedWithoutTheAttributeIsFlagged() {
        // Running at a speed potion's pace while the attribute still says the default is what a speed cheat
        // looks like. (Hopping barely uses the speed attribute - one ground tick in twelve - so this runs.)
        double fast = 0.1 * 1.4 * 1.3 * 1.6;
        Harness harness = new Harness();
        harness.feed(Harness.run(SIXTY_SECONDS, true, false, fast), 2, PlayerFlags.SPRINTING, SPRINT);
        assertTrue(harness.alerted(SpeedCheck.NAME));
    }

    @Test
    void aLegalHopperOnIceIsNeverFlagged() {
        Harness harness = new Harness();
        harness.probe.friction = 0.98;
        harness.feedJittered(Harness.run(SIXTY_SECONDS, true, true, SPRINT, 0.98), 3, PlayerFlags.SPRINTING, SPRINT);
        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
    }

    @Test
    void aLegalRunnerCrossingFromGrassOntoIceIsNeverFlagged() {
        Harness harness = new Harness();
        Harness.LegalPlayer player = new Harness.LegalPlayer();
        for (int tick = 0; tick < SIXTY_SECONDS; tick++) {
            boolean onIce = tick >= 600;
            player.friction = onIce ? 0.98 : 0.6;
            harness.probe.friction = player.friction;
            player.tick(true, true, SPRINT, 1.0, 0.42);
            if (tick % 2 == 0) {
                harness.report(Harness.START + tick * Harness.TICK, Harness.quantize(player.x, 1.0 / 4096.0),
                        Harness.quantize(player.y, 1.0 / 4096.0), 0.0, player.onGround, PlayerFlags.SPRINTING, SPRINT);
            }
        }

        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
    }

    @Test
    void aTeleportInTheMiddleOfAWindowIsNotSpeed() {
        Harness harness = new Harness();
        List<Harness.Point> legal = Harness.run(SIXTY_SECONDS, true, true, SPRINT);
        for (Harness.Point point : legal) {
            if (point.tick() % 2 != 0) {
                continue;
            }

            double x = point.x();
            if (point.tick() == 240) {
                harness.players.onTeleport(Harness.ID, Harness.START + point.tick() * Harness.TICK - 1_000_000L);
            }

            if (point.tick() >= 240) {
                x += 500.0;
            }

            harness.report(Harness.START + point.tick() * Harness.TICK, x, point.y(), 0.0, point.onGround(),
                    PlayerFlags.SPRINTING, SPRINT);
        }

        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
    }

    @Test
    void aTeleportThatOnlyArrivesAsAPositionReportIsNotSpeedEvenOnStrict() {
        // An ender pearl reaches an observer as an ordinary position report with a big step; no teleport packet.
        Harness harness = new Harness().withSettings(new CheckSettings(true, true, true, true, 1.05, 12, 1, 1.0, 1.0 / 4096.0));
        for (Harness.Point point : Harness.run(SIXTY_SECONDS, true, true, SPRINT)) {
            if (point.tick() % 2 != 0) {
                continue;
            }

            double x = point.x() + (point.tick() >= 240 ? 60.0 : 0.0);
            harness.report(Harness.START + point.tick() * Harness.TICK, x, point.y(), 0.0, point.onGround(),
                    PlayerFlags.SPRINTING, SPRINT);
        }

        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
    }

    @Test
    void aVelocityPushIsNotSpeed() {
        Harness harness = new Harness();
        for (Harness.Point point : Harness.run(SIXTY_SECONDS, true, true, SPRINT)) {
            if (point.tick() % 2 != 0) {
                continue;
            }

            double x = point.x();
            if (point.tick() == 240) {
                harness.players.onImpulse(Harness.ID, Harness.START + point.tick() * Harness.TICK - 1_000_000L);
            }

            if (point.tick() >= 240) {
                x += 12.0;
            }

            harness.report(Harness.START + point.tick() * Harness.TICK, x, point.y(), 0.0, point.onGround(),
                    PlayerFlags.SPRINTING, SPRINT);
        }

        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
    }

    @Test
    void movementInLiquidIsNotJudged() {
        Harness harness = new Harness();
        harness.probe.environment = true;
        harness.feed(scaled(Harness.run(SIXTY_SECONDS, true, true, SPRINT), 3.0), 2, PlayerFlags.SPRINTING, SPRINT);
        assertTrue(harness.alerts.isEmpty());
    }

    @Test
    void glidingWithAnElytraIsNotJudged() {
        Harness harness = new Harness();
        harness.feed(scaled(Harness.run(SIXTY_SECONDS, true, true, SPRINT), 3.0), 2, PlayerFlags.GLIDING, SPRINT);
        assertTrue(harness.alerts.isEmpty());
    }

    @Test
    void creativeFlightIsNotJudged() {
        Harness harness = new Harness();
        harness.feed(scaled(Harness.run(SIXTY_SECONDS, true, true, SPRINT), 3.0), 2, PlayerFlags.EXEMPT_MODE, SPRINT);
        assertTrue(harness.alerts.isEmpty());
    }

    @Test
    void strictNeedsOnlyOneOffendingWindowWhereBalancedNeedsTwo() {
        // A trajectory fast enough to break the strict ceiling for a stretch, but not the balanced one.
        List<Harness.Point> slightlyFast = scaled(Harness.run(240, true, true, SPRINT), 1.4);

        Harness strict = new Harness().withSettings(new CheckSettings(true, true, true, true, 1.05, 12, 1, 1.0, 1.0 / 4096.0));
        strict.feed(slightlyFast, 2, PlayerFlags.SPRINTING, SPRINT);
        assertTrue(strict.alerted(SpeedCheck.NAME), "strict should flag a 1.4x hopper");

        Harness balanced = new Harness();
        balanced.feed(slightlyFast, 2, PlayerFlags.SPRINTING, SPRINT);
        assertFalse(balanced.alerted(SpeedCheck.NAME), "balanced should not flag a 1.4x hopper");
    }
}
