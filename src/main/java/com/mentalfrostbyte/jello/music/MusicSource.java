package com.mentalfrostbyte.jello.music;

import java.util.List;

/**
 * Where the player's queue comes from - a built-in demo list today, a streaming service or a local folder
 * later. Sources only describe tracks; playing them is a {@link MusicBackend}'s job.
 */
public interface MusicSource {
    /** Shown next to the playback state, e.g. "Jello sessions". */
    String name();

    /** The tracks, in queue order. May be empty; must not be {@code null}. */
    List<Track> tracks();
}
