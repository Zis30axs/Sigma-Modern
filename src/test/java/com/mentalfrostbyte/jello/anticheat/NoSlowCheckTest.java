package com.mentalfrostbyte.jello.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.check.NoSlowCheck;
import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoSlowCheckTest {

    private static final double SPRINT = 0.13;
    /** Every flag announces, so a test can see them one by one. */
    private static final CheckSettings EAGER = new CheckSettings(true, true, true, true, 1.15, 20, 2, 1.0, 1.0 / 4096.0);

    /** A legal player who eats: sprint-hopping for a while, then using an item at the vanilla 0.2 input scale. */
    private static List<Harness.Point> eatsAfterSprinting(final int sprintTicks, final int eatTicks) {
        Harness.LegalPlayer player = new Harness.LegalPlayer();
        List<Harness.Point> points = new ArrayList<>();
        int tick = 0;
        for (; tick < sprintTicks; tick++) {
            player.tick(true, true, SPRINT, 1.0, 0.42);
            points.add(player.point(tick));
        }

        for (; tick < sprintTicks + eatTicks; tick++) {
            player.tick(false, false, 0.1, 0.2, 0.42);
            points.add(player.point(tick));
        }

        return points;
    }

    private static void feedUsing(final Harness harness, final List<Harness.Point> points, final int fromTick) {
        for (Harness.Point point : points) {
            if (point.tick() % 2 == 0) {
                harness.report(Harness.START + point.tick() * Harness.TICK, Harness.quantize(point.x(), 1.0 / 4096.0),
                        Harness.quantize(point.y(), 1.0 / 4096.0), 0.0, point.onGround(),
                        point.tick() >= fromTick ? PlayerFlags.USING_ITEM : PlayerFlags.SPRINTING, 0.1);
            }
        }
    }

    @Test
    void aPlayerWhoIsReallySlowedIsNotFlagged() {
        Harness harness = new Harness().withSettings(EAGER);
        // Momentum from the sprint carries into the meal; then the 0.2 scale takes over.
        feedUsing(harness, eatsAfterSprinting(200, 400), 200);
        assertTrue(harness.alerts.isEmpty(), harness.alerts.toString());
        assertEquals(0.0, harness.level(NoSlowCheck.NAME), 1.0E-9);
    }

    @Test
    void aPlayerWhoStartsEatingMidHopKeepsTheirGlideAndIsNotFlagged() {
        for (int mealStart = 200; mealStart < 212; mealStart++) {
            Harness harness = new Harness().withSettings(EAGER);
            feedUsing(harness, eatsAfterSprinting(mealStart, 32), mealStart);
            assertTrue(harness.alerts.isEmpty(), "meal started at tick " + mealStart + ": " + harness.alerts);
        }
    }

    @Test
    void noSlowWalkingAtFullSpeedWhileEatingIsFlagged() {
        Harness harness = new Harness().withSettings(EAGER);
        // Uses the item but never slows: walks at the ordinary speed (input factor 1) for three seconds.
        Harness.LegalPlayer player = new Harness.LegalPlayer();
        for (int tick = 0; tick < 80; tick++) {
            player.tick(false, false, 0.1, 1.0, 0.42);
            if (tick % 2 == 0) {
                harness.report(Harness.START + tick * Harness.TICK, Harness.quantize(player.x, 1.0 / 4096.0), 0.0, 0.0, true,
                        PlayerFlags.USING_ITEM, 0.1);
            }
        }

        assertTrue(harness.alerted(NoSlowCheck.NAME));
    }

    @Test
    void noSlowSprintHoppingWhileUsingAnItemIsFlagged() {
        Harness harness = new Harness().withSettings(EAGER);
        harness.feed(Harness.run(300, true, true, SPRINT), 2, PlayerFlags.USING_ITEM, SPRINT);
        assertTrue(harness.alerted(NoSlowCheck.NAME));
    }

    @Test
    void movingNormallyWithoutAnItemInUseIsNotThisChecksBusiness() {
        Harness harness = new Harness().withSettings(EAGER);
        harness.feed(Harness.run(300, true, true, SPRINT), 2, PlayerFlags.SPRINTING, SPRINT);
        assertTrue(harness.alerts.stream().noneMatch(alert -> alert.check().equals(NoSlowCheck.NAME)));
    }

    @Test
    void stoppingTheItemBeforeAWindowFillsResetsTheMomentumAllowance() {
        Harness harness = new Harness().withSettings(EAGER);
        // Ten ticks of item use, ten of free movement, repeated: no window ever fills, so nothing is judged.
        Harness.LegalPlayer player = new Harness.LegalPlayer();
        for (int tick = 0; tick < 400; tick++) {
            boolean using = (tick / 10) % 2 == 0;
            player.tick(!using, !using, SPRINT, using ? 0.2 : 1.0, 0.42);
            if (tick % 2 == 0) {
                harness.report(Harness.START + tick * Harness.TICK, Harness.quantize(player.x, 1.0 / 4096.0),
                        Harness.quantize(player.y, 1.0 / 4096.0), 0.0, player.onGround,
                        using ? PlayerFlags.USING_ITEM : PlayerFlags.SPRINTING, SPRINT);
            }
        }

        assertTrue(harness.alerts.stream().noneMatch(alert -> alert.check().equals(NoSlowCheck.NAME)), harness.alerts.toString());
    }
}
