package com.mentalfrostbyte.jello.music;

import java.util.List;

/**
 * The built-in "Jello sessions" list from the SigmaModern reference design: three short ambient pieces
 * sharing one album cover. Metadata only - there is no audio behind it until a real backend is connected.
 */
public final class DemoMusicSource implements MusicSource {
    private static final String COVER = "minecraft:textures/gui/sigma/modern/music/jello_sessions.png";
    private static final List<Track> TRACKS = List.of(
        new Track("jello-blue-hour", "Blue hour", "Jello", "Jello sessions", "Ambient", 48_000L, COVER),
        new Track("jello-drift", "Drift", "Jello", "Jello sessions", "Ambient", 48_000L, COVER),
        new Track("jello-afterglow", "Afterglow", "Jello", "Jello sessions", "Ambient", 48_000L, COVER)
    );

    @Override
    public String name() {
        return "Jello sessions";
    }

    @Override
    public List<Track> tracks() {
        return TRACKS;
    }
}
