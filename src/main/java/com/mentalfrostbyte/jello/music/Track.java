package com.mentalfrostbyte.jello.music;

import org.jspecify.annotations.Nullable;

/**
 * One playable item, as a {@link MusicSource} describes it. Nothing here is tied to how it is fetched or
 * played: a future streaming source fills the same fields from its own API.
 *
 * @param id         stable within its source (used to keep the selection across a queue reload)
 * @param title      display title
 * @param artist     performer, or empty
 * @param album      album or collection name, or empty
 * @param tag        a short genre/mood label ("Ambient"), or empty
 * @param durationMs length from the metadata; 0 when unknown (the backend may know better once loaded)
 * @param cover      a resource location of the artwork ({@code namespace:path}), or {@code null} for none -
 *                   interfaces paint a generated cover instead
 */
public record Track(String id, String title, String artist, String album, String tag, long durationMs, @Nullable String cover) {
    public Track {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Track id must not be blank");
        if (title == null) throw new IllegalArgumentException("Track title must not be null");
        artist = artist == null ? "" : artist;
        album = album == null ? "" : album;
        tag = tag == null ? "" : tag;
        durationMs = Math.max(0L, durationMs);
    }
}
