package com.mentalfrostbyte.jello.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Sigma's music player: a queue from a {@link MusicSource}, played through a {@link MusicBackend}.
 * Interfaces (the SigmaModern player panel, its in-game island) only read this state and call the
 * transport methods; swapping in a streaming source or a real audio backend changes nothing above here.
 *
 * <p>Behaviour follows the SigmaModern reference: choosing a track, next and previous all start playback;
 * a finished track moves on to the next, and the queue wraps around. "Previous" first restarts the current
 * track once it has played for a few seconds, as media players usually do. Registered on the event bus so
 * a finished track is noticed every client tick, in menus as well as in game.</p>
 */
public final class MusicPlayer {
    /** Past this point "previous" restarts the current track instead of going back one. */
    public static final long RESTART_THRESHOLD_MS = 3_000L;
    /** Unplayable tracks in a row after which the player stops skipping (e.g. offline) instead of cycling. */
    public static final int MAX_SKIPS = 3;
    public static final float DEFAULT_VOLUME = 0.35F;

    private final MusicBackend backend;
    private MusicSource source;
    private List<Track> queue = List.of();
    private int index = -1;
    private float volume = DEFAULT_VOLUME;
    private boolean failureHandled;
    private int failuresInARow;
    private @Nullable String problem;

    public MusicPlayer(MusicBackend backend, MusicSource source) {
        this.backend = backend;
        this.backend.setVolume(this.volume);
        setSource(source);
    }

    /** Replaces the queue with {@code source}'s tracks, paused at the first one. */
    public void setSource(MusicSource source) {
        setSource(source, 0, false);
    }

    /** Replaces the queue with {@code source}'s tracks at {@code start} (e.g. the search result that was picked). */
    public void setSource(MusicSource source, int start, boolean play) {
        this.source = source;
        this.queue = List.copyOf(source.tracks());
        this.index = -1;
        this.failuresInARow = 0;
        this.problem = null;
        if (this.queue.isEmpty()) {
            this.backend.pause();
            return;
        }
        load(Math.floorMod(start, this.queue.size()));
        if (play) this.backend.play();
    }

    // --- state ------------------------------------------------------------------------------------------

    public String sourceName() {
        return this.source.name();
    }

    public List<Track> queue() {
        return this.queue;
    }

    /** Index of the current track in {@link #queue()}, or -1 when the queue is empty. */
    public int index() {
        return this.index;
    }

    public @Nullable Track current() {
        return this.index < 0 ? null : this.queue.get(this.index);
    }

    /** Playback is wanted (it may still be {@linkplain #isBuffering() buffering}). */
    public boolean isPlaying() {
        return this.index >= 0 && this.backend.isPlaying();
    }

    /** Waiting for the stream: resolving, downloading, or seeking ahead of the download. */
    public boolean isBuffering() {
        return isPlaying() && this.backend.isBuffering();
    }

    /** Only a short preview of the current track is available (a paid song without a signed-in account). */
    public boolean isPreview() {
        return this.index >= 0 && this.backend.isPreview();
    }

    /** Why the last track couldn't be played, until something plays again; {@code null} when all is well. */
    public @Nullable String problem() {
        return this.problem;
    }

    /** What the music sounds like now (heard, not decoded ahead), for visuals; {@code null} when unknown. */
    public @Nullable Spectrum spectrum() {
        return this.index < 0 ? null : this.backend.spectrum(positionMs());
    }

    /** Whether anything is audible: false with {@link SilentBackend}, which only keeps time. */
    public boolean producesSound() {
        return this.backend.producesSound();
    }

    public long positionMs() {
        return this.index < 0 ? 0L : this.backend.positionMs();
    }

    /** The current track's length: the backend's own figure once it knows one, else the metadata's. */
    public long durationMs() {
        Track track = current();
        if (track == null) return 0L;
        long fromBackend = this.backend.durationMs();
        return fromBackend > 0L ? fromBackend : track.durationMs();
    }

    /** 0..1 through the current track (0 when its length is unknown). */
    public float progress() {
        long duration = durationMs();
        return duration <= 0L ? 0F : Math.min(1F, positionMs() / (float)duration);
    }

    public float volume() {
        return this.volume;
    }

    // --- transport --------------------------------------------------------------------------------------

    public void play() {
        if (this.index < 0) return;
        if (this.backend.failure() != null) {
            // Asked to play a track that already failed: try it again from the start.
            load(this.index);
        }
        this.backend.play();
    }

    public void pause() {
        this.backend.pause();
    }

    public void toggle() {
        if (isPlaying()) pause();
        else play();
    }

    public void next() {
        if (!this.queue.isEmpty()) select(this.index + 1, true);
    }

    public void previous() {
        if (this.queue.isEmpty()) return;
        if (positionMs() > RESTART_THRESHOLD_MS) {
            seek(0L);
            play();
        } else {
            select(this.index - 1, true);
        }
    }

    /** Makes queue entry {@code i} current (wrapping out-of-range indices) and optionally starts it. */
    public void select(int i, boolean play) {
        if (this.queue.isEmpty()) return;
        load(Math.floorMod(i, this.queue.size()));
        if (play) this.backend.play();
    }

    /** Loads the current track again from the start, keeping it playing or paused (e.g. a preview after signing in). */
    public void reloadCurrent() {
        if (this.index < 0) return;
        boolean playing = this.backend.isPlaying();
        load(this.index);
        if (playing) this.backend.play();
    }

    public void seek(long positionMs) {
        if (this.index < 0) return;
        long duration = durationMs();
        long clamped = Math.max(0L, duration > 0L ? Math.min(positionMs, duration) : positionMs);
        this.backend.seek(clamped);
    }

    /** Seeks to a 0..1 fraction of the current track. */
    public void seekFraction(float fraction) {
        seek(Math.round(Math.max(0F, Math.min(1F, fraction)) * durationMs()));
    }

    public void setVolume(float volume) {
        if (!Float.isFinite(volume)) return;
        this.volume = Math.max(0F, Math.min(1F, volume));
        this.backend.setVolume(this.volume);
    }

    /**
     * Reacts to the backend: a finished track moves on; an unplayable one is skipped - up to {@link #MAX_SKIPS}
     * in a row, after which the player pauses and keeps the reason as {@link #problem()}. Called every tick.
     */
    public void update() {
        if (this.index < 0) return;
        String failure = this.backend.failure();
        if (failure != null) {
            if (this.failureHandled) return;
            this.failureHandled = true;
            this.problem = failure;
            this.failuresInARow++;
            if (this.backend.isPlaying() && this.failuresInARow < MAX_SKIPS && this.queue.size() > 1) {
                select(this.index + 1, true);
            } else {
                this.backend.pause();
            }
            return;
        }
        if (!isPlaying()) return;
        if (!this.backend.isBuffering() && positionMs() > 0L) {
            // Something is actually playing again.
            this.failuresInARow = 0;
            this.problem = null;
        }
        if (this.backend.hasEnded()) next();
    }

    @EventTarget
    public void onTick(EventTick event) {
        if (event.isPre()) update();
    }

    private void load(int i) {
        this.index = i;
        this.failureHandled = false;
        this.backend.load(this.queue.get(i));
    }

    /** Stops playback and releases the backend (shutdown). */
    public void close() {
        this.backend.pause();
        this.backend.close();
    }

    // --- config -----------------------------------------------------------------------------------------

    /** Reads the saved volume. A missing or malformed value is ignored rather than failing startup. */
    public void read(JsonObject config) {
        JsonElement music = config.get("music");
        if (music == null || !music.isJsonObject()) return;
        JsonElement volume = music.getAsJsonObject().get("volume");
        if (volume == null || !volume.isJsonPrimitive() || !volume.getAsJsonPrimitive().isNumber()) return;
        setVolume(volume.getAsFloat());
    }

    /** Writes the volume into the {@code "music"} settings, keeping whatever else is saved there (lyric settings). */
    public void write(JsonObject config) {
        JsonElement existing = config.get("music");
        JsonObject music = existing != null && existing.isJsonObject() ? existing.getAsJsonObject() : new JsonObject();
        music.addProperty("volume", this.volume);
        config.add("music", music);
    }
}
