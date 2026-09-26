package com.mentalfrostbyte.jello.music;

import java.util.List;

/** A fixed list of tracks under a name - a chart, a search's results. */
public record ListSource(String name, List<Track> tracks) implements MusicSource {
    public ListSource {
        tracks = List.copyOf(tracks);
    }
}
