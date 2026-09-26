package com.mentalfrostbyte.jello.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The player's queue and transport rules, on the time-only backend with a hand-driven clock. */
class MusicPlayerTest {
    private long nanos;

    private MusicPlayer player(int tracks) {
        List<Track> list = new java.util.ArrayList<>();
        for (int i = 0; i < tracks; i++) list.add(new Track("t" + i, "Track " + i, "", "", "", 10_000L, null));
        MusicSource source = new MusicSource() {
            @Override public String name() { return "Test"; }
            @Override public List<Track> tracks() { return list; }
        };
        return new MusicPlayer(new SilentBackend(() -> this.nanos), source);
    }

    private void advance(long ms) {
        this.nanos += ms * 1_000_000L;
    }

    @Test
    void startsPausedOnTheFirstTrack() {
        MusicPlayer player = player(3);
        assertEquals(0, player.index());
        assertFalse(player.isPlaying());
        assertEquals(0L, player.positionMs());
    }

    @Test
    void positionFollowsTheClockOnlyWhilePlaying() {
        MusicPlayer player = player(1);
        player.play();
        advance(2_500);
        assertEquals(2_500L, player.positionMs());
        player.pause();
        advance(4_000);
        assertEquals(2_500L, player.positionMs());
    }

    @Test
    void nextAndPreviousWrapAroundTheQueue() {
        MusicPlayer player = player(3);
        player.previous();
        assertEquals(2, player.index());
        assertTrue(player.isPlaying(), "previous starts playback");
        player.next();
        assertEquals(0, player.index());
        player.next();
        assertEquals(1, player.index());
    }

    @Test
    void previousRestartsATrackThatHasPlayedForAWhile() {
        MusicPlayer player = player(3);
        player.select(1, true);
        advance(MusicPlayer.RESTART_THRESHOLD_MS + 1_000);
        player.previous();
        assertEquals(1, player.index(), "stays on the same track");
        assertEquals(0L, player.positionMs());
        player.previous();
        assertEquals(0, player.index(), "a second press goes back one");
    }

    @Test
    void aFinishedTrackMovesOnAndTheQueueWraps() {
        MusicPlayer player = player(2);
        player.select(1, true);
        advance(10_000);
        player.update();
        assertEquals(0, player.index());
        assertTrue(player.isPlaying());
        assertEquals(0L, player.positionMs());
    }

    @Test
    void seekIsClampedToTheTrack() {
        MusicPlayer player = player(1);
        player.seek(-5_000);
        assertEquals(0L, player.positionMs());
        player.seek(99_000);
        assertEquals(10_000L, player.positionMs());
        player.seekFraction(0.5F);
        assertEquals(5_000L, player.positionMs());
    }

    @Test
    void volumeIsClampedAndNonFiniteValuesAreIgnored() {
        MusicPlayer player = player(1);
        player.setVolume(1.7F);
        assertEquals(1F, player.volume());
        player.setVolume(-0.2F);
        assertEquals(0F, player.volume());
        player.setVolume(Float.NaN);
        assertEquals(0F, player.volume());
    }

    @Test
    void savedVolumeRoundTripsAndBadValuesAreIgnored() {
        MusicPlayer player = player(1);
        player.setVolume(0.6F);
        JsonObject config = new JsonObject();
        player.write(config);

        MusicPlayer restored = player(1);
        restored.read(config);
        assertEquals(0.6F, restored.volume(), 1e-6F);

        MusicPlayer tolerant = player(1);
        tolerant.read(JsonParser.parseString("{\"music\": {\"volume\": \"loud\"}}").getAsJsonObject());
        tolerant.read(JsonParser.parseString("{\"music\": 5}").getAsJsonObject());
        tolerant.read(new JsonObject());
        assertEquals(MusicPlayer.DEFAULT_VOLUME, tolerant.volume());
    }

    @Test
    void anEmptyQueueIgnoresEverything() {
        MusicPlayer player = player(0);
        player.play();
        player.next();
        player.previous();
        player.seek(1_000);
        player.update();
        assertEquals(-1, player.index());
        assertNull(player.current());
        assertFalse(player.isPlaying());
        assertEquals(0L, player.durationMs());
    }

    @Test
    void reloadingRestartsTheTrackButKeepsPlayingOrPaused() {
        MusicPlayer player = player(3);
        player.select(1, true);
        advance(4_000);
        player.reloadCurrent();
        assertEquals(1, player.index());
        assertTrue(player.isPlaying());
        assertEquals(0L, player.positionMs());

        player.pause();
        advance(2_000);
        player.reloadCurrent();
        assertFalse(player.isPlaying());
        assertEquals(0L, player.positionMs());
    }
}
