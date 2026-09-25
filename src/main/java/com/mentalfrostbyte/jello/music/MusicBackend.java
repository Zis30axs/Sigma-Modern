package com.mentalfrostbyte.jello.music;

import org.jspecify.annotations.Nullable;

/**
 * The engine that actually plays a {@link Track}. {@link MusicPlayer} drives it and owns the queue; a
 * backend only ever holds one loaded track.
 *
 * <p>Every method returns at once and is called from the game thread: a backend that has to resolve, download
 * or decode does that on its own threads and only <em>reports</em> what happened ({@link #isBuffering()},
 * {@link #hasEnded()}, {@link #failure()}), which the player reacts to in {@link MusicPlayer#update()}. It
 * never calls back into the player itself.</p>
 */
public interface MusicBackend {
    /** False for a backend that only keeps time; interfaces say so rather than look broken. */
    boolean producesSound();

    /** Stops whatever was loaded and starts loading {@code track}, paused at 0. */
    void load(Track track);

    void play();

    void pause();

    /** Whether playback is wanted - it may still be buffering. */
    boolean isPlaying();

    /** Current position in the loaded track, in milliseconds. */
    long positionMs();

    /** Moves to {@code positionMs} (the player has already clamped it), keeping the play/pause state. */
    void seek(long positionMs);

    /** 0..1. */
    void setVolume(float volume);

    /** The loaded track's real length if the backend knows it, else -1 (the metadata's length is used). */
    default long durationMs() {
        return -1L;
    }

    /** Playback is wanted but waiting for data (resolving, downloading, seeking ahead of the download). */
    default boolean isBuffering() {
        return false;
    }

    /** The loaded track played to its end. */
    boolean hasEnded();

    /** Why the loaded track can't be played (unavailable, network), or {@code null}. */
    default @Nullable String failure() {
        return null;
    }

    /** Only a short preview of the loaded track is available. */
    default boolean isPreview() {
        return false;
    }

    /** What the music sounds like at {@code positionMs} (for visuals), or {@code null} when the backend can't tell. */
    default @Nullable Spectrum spectrum(long positionMs) {
        return null;
    }

    /** Releases whatever the backend holds (threads, streams, audio lines). */
    default void close() {
    }
}
