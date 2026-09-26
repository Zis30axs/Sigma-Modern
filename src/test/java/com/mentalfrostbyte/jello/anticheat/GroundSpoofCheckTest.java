package com.mentalfrostbyte.jello.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.check.GroundSpoofCheck;
import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import org.junit.jupiter.api.Test;

class GroundSpoofCheckTest {

    @Test
    void anHonestLandingIsNotSpoofing() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        harness.report(Harness.START + 2 * Harness.TICK, 0.0, 1.0, false);
        harness.report(Harness.START + 4 * Harness.TICK, 0.0, 0.0, true);
        assertTrue(harness.alerts.isEmpty());
        assertEquals(0.0, harness.level(GroundSpoofCheck.NAME), 1.0E-9);
    }

    @Test
    void claimingGroundInMidAirIsFlaggedAndAnnouncedAfterAFewReports() {
        Harness harness = new Harness();
        for (int i = 0; i < 6; i++) {
            harness.report(Harness.START + i * 2 * Harness.TICK, 0.0, 5.0, true);
        }

        assertTrue(harness.alerted(GroundSpoofCheck.NAME));
        assertTrue(harness.level(GroundSpoofCheck.NAME) >= 3.0);
    }

    @Test
    void oneStrayReportIsNotAnnouncedAndFadesAway() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 5.0, true);
        assertTrue(harness.alerts.isEmpty(), "one flag is under the alert level");
        assertEquals(1.0, harness.level(GroundSpoofCheck.NAME), 1.0E-9);

        for (int i = 1; i <= 40; i++) {
            harness.report(Harness.START + i * 2 * Harness.TICK, 0.0, 0.0, true);
        }

        assertEquals(0.0, harness.level(GroundSpoofCheck.NAME), 1.0E-9);
    }

    @Test
    void aMovingPlayerGetsSidewaysSlackForTheStepPastALedge() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        harness.report(Harness.START + 2 * Harness.TICK, 0.5, 0.0, true);
        assertTrue(harness.probe.lastHorizontalSlack >= 0.5, "slack was " + harness.probe.lastHorizontalSlack);
    }

    @Test
    void aStandingPlayerGetsAlmostNoSlack() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        harness.report(Harness.START + 60 * Harness.TICK, 0.0, 0.0, true);
        assertTrue(harness.probe.lastHorizontalSlack < 0.01, "slack was " + harness.probe.lastHorizontalSlack);
        assertTrue(harness.probe.lastVerticalSlack < 0.01, "slack was " + harness.probe.lastVerticalSlack);
    }

    @Test
    void theOldProtocolsCoarsePositionsWidenTheSlack() {
        Harness harness = new Harness().withSettings(CheckSettings.balanced().withQuantStep(1.0 / 32.0));
        harness.report(Harness.START, 0.0, 0.0, true);
        harness.report(Harness.START + 60 * Harness.TICK, 0.0, 0.0, true);
        assertTrue(harness.probe.lastHorizontalSlack >= 2.0 / 32.0 - 1.0E-9);
    }

    @Test
    void denyingGroundWhileStandingStillOnSolidGroundIsFlagged() {
        Harness harness = new Harness().withSettings(new CheckSettings(true, true, true, true, 1.15, 20, 2, 1.0, 1.0 / 4096.0));
        harness.report(Harness.START, 0.0, 0.0, true);
        for (int i = 1; i <= 6; i++) {
            // Three seconds apart: what the server's forced periodic report looks like for a motionless player.
            harness.report(Harness.START + i * 60 * Harness.TICK, 0.0, 0.0, false);
        }

        assertTrue(harness.alerted(GroundSpoofCheck.NAME));
    }

    @Test
    void anAirborneFlagWhileMovingIsNotTheReverseLie() {
        Harness harness = new Harness().withSettings(new CheckSettings(true, true, true, true, 1.15, 20, 2, 1.0, 1.0 / 4096.0));
        harness.report(Harness.START, 0.0, 0.0, true);
        for (int i = 1; i <= 20; i++) {
            harness.report(Harness.START + i * 2 * Harness.TICK, i * 0.3, 0.0, false);
        }

        assertFalse(harness.alerted(GroundSpoofCheck.NAME));
    }

    @Test
    void anUnloadedChunkIsNotJudged() {
        Harness harness = new Harness();
        harness.probe.loaded = false;
        for (int i = 0; i < 6; i++) {
            harness.report(Harness.START + i * 2 * Harness.TICK, 0.0, 5.0, true);
        }

        assertTrue(harness.alerts.isEmpty());
    }

    @Test
    void aBlockChangingUnderThePlayerIsNotJudgedForAMoment() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0.0, 0.0, true);
        // The floor under them is broken, and the client's copy of the world has already changed.
        harness.players.onBlocksChanged(0.0, -1.0, 0.0, 1.0, 0.0, 1.0, Harness.START + 1_000_000L);
        harness.probe.floorY = -50.0;
        harness.report(Harness.START + 2 * Harness.TICK, 0.0, 0.0, true);
        assertTrue(harness.alerts.isEmpty());
        assertEquals(0.0, harness.level(GroundSpoofCheck.NAME), 1.0E-9);
    }

    @Test
    void aPlayerInSpectatorOrCreativeIsNotJudged() {
        Harness harness = new Harness();
        for (int i = 0; i < 6; i++) {
            harness.report(Harness.START + i * 2 * Harness.TICK, 0.0, 5.0, 0.0, true, PlayerFlags.EXEMPT_MODE, 0.1);
        }

        assertTrue(harness.alerts.isEmpty());
    }
}
