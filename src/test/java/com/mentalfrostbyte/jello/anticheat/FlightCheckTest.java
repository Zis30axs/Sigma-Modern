package com.mentalfrostbyte.jello.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.anticheat.check.FlightCheck;
import com.mentalfrostbyte.jello.anticheat.observe.ObservedPlayers;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlightCheckTest {

    /** A legal player who jumps in place over and over. */
    private static List<Harness.Point> hopsInPlace(final int ticks, final double jumpVelocity) {
        Harness.LegalPlayer player = new Harness.LegalPlayer();
        List<Harness.Point> points = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            player.tick(false, true, 0.1, 0.0, jumpVelocity);
            points.add(player.point(tick));
        }

        return points;
    }

    /** Free fall from {@code height}: what gravity alone does to a player. */
    private static List<Harness.Point> fallFrom(final double height) {
        List<Harness.Point> points = new ArrayList<>();
        double y = height;
        double vy = 0.0;
        int tick = 0;
        points.add(new Harness.Point(tick++, 0.0, y, true));
        while (true) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
            boolean landed = y <= 0.0;
            if (landed) {
                y = 0.0;
            }

            points.add(new Harness.Point(tick++, 0.0, y, landed));
            if (landed) {
                return points;
            }
        }
    }

    @Test
    void ordinaryJumpingIsNeverFlagged() {
        Harness harness = new Harness();
        harness.feed(hopsInPlace(1200, 0.42), 2, 0, Sample.DEFAULT_MOVE_SPEED);
        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
        assertEquals(0.0, harness.level(FlightCheck.NAME), 1.0E-9);
    }

    @Test
    void jumpingUnderNetworkJitterIsNeverFlagged() {
        for (long seed = 1; seed <= 12; seed++) {
            Harness harness = new Harness();
            harness.feedJittered(hopsInPlace(1200, 0.42), seed, 0, Sample.DEFAULT_MOVE_SPEED);
            assertTrue(harness.alerts.isEmpty(), "seed " + seed + ": " + harness.alerts);
        }
    }

    @Test
    void aLongFallIsNotHovering() {
        Harness harness = new Harness();
        List<Harness.Point> fall = fallFrom(200.0);
        // The player was standing on a tower, then walked off: one grounded report up there, the rest in the air,
        // and the last one on the ground far below.
        for (Harness.Point point : fall) {
            if (point.tick() % 2 == 0) {
                harness.probe.floorY = point.tick() == 0 ? 200.0 : 0.0;
                harness.report(Harness.START + point.tick() * Harness.TICK, 0.0, point.y(), point.onGround());
            }
        }

        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
    }

    @Test
    void aHighJumpIsFlagged() {
        Harness harness = new Harness();
        // A jump velocity of 0.9 lifts a player about 4 blocks; the attribute still says 0.42.
        harness.feed(hopsInPlace(1200, 0.9), 2, 0, Sample.DEFAULT_MOVE_SPEED);
        assertTrue(harness.alerted(FlightCheck.NAME), "a 4 block jump must be flagged");
    }

    @Test
    void aRaisedJumpStrengthAttributeMakesTheHighJumpLegal() {
        Harness harness = new Harness();
        // Jump strength 0.9 shown in the attribute: the server says this player jumps that high.
        for (Harness.Point point : hopsInPlace(1200, 0.9)) {
            if (point.tick() % 2 == 0) {
                harness.players.onPosition(Harness.ID, point.x(), point.y(), 0.0, point.onGround(),
                        new ObservedPlayers.Snapshot(0, 0.1, 0.9, Sample.DEFAULT_GRAVITY, false),
                        Harness.START + point.tick() * Harness.TICK, harness.settings);
            }
        }

        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
    }

    @Test
    void hoveringInTheAirIsFlagged() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        // A block up - within any jump's reach, so it is the hovering that gets caught, not the height.
        for (int tick = 2; tick <= 200; tick += 2) {
            harness.report(Harness.START + tick * Harness.TICK, 0.0, 1.0, false);
        }

        assertTrue(harness.alerted(FlightCheck.NAME), "ten seconds of hovering must be flagged");
        assertTrue(harness.alerts.stream().allMatch(alert -> alert.detail().startsWith("hovering")), harness.alerts.toString());
    }

    @Test
    void hoveringForAShortWhileAtTheTopOfAJumpIsNotHovering() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        for (int tick = 2; tick <= 20; tick += 2) {
            harness.report(Harness.START + tick * Harness.TICK, 0.0, 1.2, false);
        }

        assertTrue(harness.alerts.isEmpty(), "a second of hang time is under the hover window");
    }

    @Test
    void flyingUpwardsIsFlagged() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        for (int tick = 2; tick <= 200; tick += 2) {
            harness.report(Harness.START + tick * Harness.TICK, 0.0, tick * 0.05, false);
        }

        assertTrue(harness.alerted(FlightCheck.NAME));
    }

    @Test
    void hoveringWithUnknownEffectsIsNotJudged() {
        // Slow falling and levitation are invisible except for their particles.
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        for (int tick = 2; tick <= 200; tick += 2) {
            harness.players.onPosition(Harness.ID, 0.0, 6.0, 0.0, false,
                    new ObservedPlayers.Snapshot(0, 0.1, 0.42, Sample.DEFAULT_GRAVITY, true),
                    Harness.START + tick * Harness.TICK, harness.settings);
        }

        assertTrue(harness.alerts.isEmpty());
    }

    @Test
    void aShiftedGravityAttributeIsNotJudged() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        for (int tick = 2; tick <= 200; tick += 2) {
            harness.players.onPosition(Harness.ID, 0.0, 6.0, 0.0, false,
                    new ObservedPlayers.Snapshot(0, 0.1, 0.42, 0.01, false), Harness.START + tick * Harness.TICK, harness.settings);
        }

        assertTrue(harness.alerts.isEmpty());
    }

    @Test
    void hoveringInsideLiquidOrOnAClimbableIsNotJudged() {
        Harness harness = new Harness();
        harness.probe.environment = true;
        harness.report(Harness.START, 0.0, 0.0, true);
        for (int tick = 2; tick <= 200; tick += 2) {
            harness.report(Harness.START + tick * Harness.TICK, 0.0, 6.0, false);
        }

        assertTrue(harness.alerts.isEmpty());
    }

    @Test
    void aBounceOffASlimeBlockIsNotAHighJump() {
        // The launch happens next to slime, which the environment probe reports, so the whole stretch is dropped.
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        harness.probe.environment = true;
        harness.report(Harness.START + 2 * Harness.TICK, 0.0, 3.0, false);
        harness.probe.environment = false;
        for (int tick = 4; tick <= 40; tick += 2) {
            harness.report(Harness.START + tick * Harness.TICK, 0.0, 8.0 - (tick - 4) * 0.2, false);
        }

        assertTrue(harness.alerts.isEmpty());
    }

    @Test
    void anAirborneFlagOverSolidGroundIsNotFlightsBusiness() {
        // That is the ground check's job (it flags the lie); flight must not flag the same report as hovering.
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        for (int tick = 2; tick <= 200; tick += 2) {
            harness.report(Harness.START + tick * Harness.TICK, 0.0, 0.0, false);
        }

        assertTrue(harness.alerts.stream().noneMatch(alert -> alert.check().equals(FlightCheck.NAME)));
    }
}
