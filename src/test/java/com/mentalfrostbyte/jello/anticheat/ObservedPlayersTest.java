package com.mentalfrostbyte.jello.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.anticheat.observe.ObservedPlayers;
import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservedPlayersTest {

    @Test
    void aReportForAnUntrackedPlayerIsIgnored() {
        Harness harness = new Harness();
        assertNull(harness.players.onPosition(999, 0, 0, 0, true,
                new ObservedPlayers.Snapshot(0, 0.1, 0.42, 0.08, false), Harness.START, harness.settings));
    }

    @Test
    void theFirstTwoSecondsAfterAPlayerAppearsAreExempt() {
        Harness harness = new Harness();
        assertTrue(harness.report(1_000_000_000L, 0, 0, true).exempt());
        assertFalse(harness.report(Harness.START, 0, 0, true).exempt());
    }

    @Test
    void aTeleportExemptsTheNextFewReportsOnly() {
        Harness harness = new Harness();
        harness.players.onTeleport(Harness.ID, Harness.START);
        assertTrue(harness.report(Harness.START + 100_000_000L, 0, 0, true).exempt());
        assertFalse(harness.report(Harness.START + 400_000_000L, 0, 0, true).exempt());
    }

    @Test
    void aStepTooBigForARelativeReportIsTreatedAsATeleport() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0, 0, true);
        assertFalse(harness.report(Harness.START + 2 * Harness.TICK, 1, 0, true).exempt());
        // 40 blocks in two ticks: no legal player does that; it is how a pearl arrives.
        assertTrue(harness.report(Harness.START + 4 * Harness.TICK, 41, 0, true).exempt());
        assertFalse(harness.report(Harness.START + 10 * Harness.TICK, 41, 0, true).exempt());
    }

    @Test
    void aFallingPlayerAtTerminalVelocityIsNotATeleport() {
        Harness harness = new Harness();
        harness.probe.floorY = -500;
        harness.report(Harness.START, 0, 100, false);
        // 3.92 blocks a tick, two ticks per report.
        assertFalse(harness.report(Harness.START + 2 * Harness.TICK, 0, 92.16, false).exempt());
    }

    @Test
    void aVelocityPushExemptsAboutASecond() {
        Harness harness = new Harness();
        harness.players.onImpulse(Harness.ID, Harness.START);
        assertTrue(harness.report(Harness.START + 900_000_000L, 0, 0, true).exempt());
        assertFalse(harness.report(Harness.START + 1_100_000_000L, 0, 0, true).exempt());
    }

    @Test
    void aBlockChangeNearThePlayerExemptsAndOneFarAwayDoesNot() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0, 0, true);

        harness.players.onBlocksChanged(100, 0, 100, 101, 1, 101, Harness.START + 1_000_000L);
        assertFalse(harness.report(Harness.START + 100_000_000L, 0, 0, true).exempt());

        harness.players.onBlocksChanged(2, 0, 2, 3, 1, 3, Harness.START + 200_000_000L);
        assertTrue(harness.report(Harness.START + 300_000_000L, 0, 0, true).exempt());
        assertFalse(harness.report(Harness.START + 900_000_000L, 0, 0, true).exempt());
    }

    @Test
    void anUnloadedChunkMakesTheSampleUnjudgeable() {
        Harness harness = new Harness();
        harness.probe.loaded = false;
        Sample sample = harness.report(Harness.START, 0, 0, true);
        assertFalse(sample.chunkLoaded());
        assertFalse(sample.judgeable());
    }

    @Test
    void theSnapshotAndTheWorldAnswersEndUpInTheSample() {
        Harness harness = new Harness();
        harness.probe.friction = 0.98;
        harness.probe.environment = true;
        Sample sample = harness.players.onPosition(Harness.ID, 1, 2, 3, false,
                new ObservedPlayers.Snapshot(PlayerFlags.SPRINTING | PlayerFlags.USING_ITEM, 0.13, 0.5, 0.07, true),
                Harness.START, harness.settings);
        assertEquals(0.98, sample.friction(), 1.0E-9);
        assertTrue(sample.unjudgedEnvironment());
        assertTrue(sample.has(PlayerFlags.SPRINTING));
        assertTrue(sample.has(PlayerFlags.USING_ITEM));
        assertFalse(sample.has(PlayerFlags.SNEAKING));
        assertEquals(0.13, sample.moveSpeed(), 1.0E-9);
        assertEquals(0.5, sample.jumpStrength(), 1.0E-9);
        assertEquals(0.07, sample.gravity(), 1.0E-9);
        assertTrue(sample.unknownEffects());
        assertFalse(sample.onGround());
    }

    @Test
    void trackingTheSamePlayerAgainKeepsTheirHistoryButANewPlayerOnTheSameIdStartsFresh() {
        Harness harness = new Harness();
        harness.report(Harness.START, 0, 0, true);
        TrackedPlayer same = harness.players.track(Harness.ID, harness.player.uuid(), "Renamed", Harness.START);
        assertSame(harness.player, same);
        assertEquals("Renamed", same.name());
        assertEquals(1, same.sampleCount());

        TrackedPlayer other = harness.players.track(Harness.ID, UUID.randomUUID(), "Alex", Harness.START);
        assertEquals(0, other.sampleCount());
    }

    @Test
    void untrackingForgetsThePlayerAndClearForgetsEveryone() {
        Harness harness = new Harness();
        assertNotNull(harness.players.get(Harness.ID));
        harness.players.untrack(Harness.ID);
        assertNull(harness.players.get(Harness.ID));

        harness.players.track(1, UUID.randomUUID(), "A", 0L);
        harness.players.track(2, UUID.randomUUID(), "B", 0L);
        harness.players.clear();
        assertTrue(harness.players.all().isEmpty());
    }

    @Test
    void forgettingAPlayerZeroesTheirLevelsAndOnlyTheirs() {
        Harness harness = new Harness();
        harness.player.buffer("Speed").flag(4.0);
        TrackedPlayer other = harness.players.track(2, UUID.randomUUID(), "Alex", 0L);
        other.buffer("Speed").flag(2.0);

        harness.players.clearLevels(harness.player.uuid());
        assertEquals(0.0, harness.player.buffer("Speed").level(), 1.0E-9);
        assertEquals(2.0, other.buffer("Speed").level(), 1.0E-9);

        harness.players.clearLevels();
        assertEquals(0.0, other.buffer("Speed").level(), 1.0E-9);
    }

    @Test
    void thePlayersSampleHistoryIsBounded() {
        Harness harness = new Harness();
        for (int i = 0; i < 1000; i++) {
            harness.report(Harness.START + i * 100_000_000L, 0, 0, true);
        }

        assertEquals(400, harness.player.sampleCount());
    }
}
