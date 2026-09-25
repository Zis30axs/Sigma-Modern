package com.mentalfrostbyte.jello.music;

import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * A backend that keeps time and nothing else: "playing" advances the position by the wall clock, so pauses,
 * menus and lag spikes don't bend it, and a track ends when its metadata length has passed. It exists so the
 * player and its interfaces work end to end before a real audio or streaming backend is connected. Its
 * {@link #spectrum} is made up - slowly shifting bands and a beat every half second (120 BPM) - so visuals can be
 * seen, and captured the same way every time, offline.
 */
public final class SilentBackend implements MusicBackend {
    private final LongSupplier nanoClock;
    private long durationMs;
    private long basePositionMs;
    private long startedAt;
    private boolean playing;

    /** @param nanoClock monotonic nanoseconds ({@code System::nanoTime}; tests pass their own) */
    public SilentBackend(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    @Override
    public boolean producesSound() {
        return false;
    }

    @Override
    public void load(Track track) {
        this.playing = false;
        this.basePositionMs = 0L;
        this.durationMs = track.durationMs();
    }

    @Override
    public void play() {
        if (this.playing) return;
        this.startedAt = this.nanoClock.getAsLong();
        this.playing = true;
    }

    @Override
    public void pause() {
        if (!this.playing) return;
        this.basePositionMs = positionMs();
        this.playing = false;
    }

    @Override
    public boolean isPlaying() {
        return this.playing;
    }

    @Override
    public long positionMs() {
        long position = this.basePositionMs;
        if (this.playing) position += (this.nanoClock.getAsLong() - this.startedAt) / 1_000_000L;
        return this.durationMs > 0L ? Math.min(position, this.durationMs) : position;
    }

    @Override
    public void seek(long positionMs) {
        this.basePositionMs = Math.max(0L, positionMs);
        this.startedAt = this.nanoClock.getAsLong();
    }

    @Override
    public boolean hasEnded() {
        return this.playing && this.durationMs > 0L && positionMs() >= this.durationMs;
    }

    @Override
    public @Nullable Spectrum spectrum(long positionMs) {
        if (!this.playing) return null;
        double t = positionMs / 1000.0;
        float[] bands = new float[AudioAnalyzer.BANDS];
        double sinceBeat = (positionMs % 500L) / 500.0;
        float kick = (float)Math.exp(-sinceBeat * 6.0);
        for (int b = 0; b < bands.length; b++) {
            double shape = 0.75 - 0.35 * b / bands.length;
            double wave = 0.5 + 0.5 * Math.sin(t * (1.3 + b * 0.07) + b * 0.9);
            float bass = b < 8 ? kick * (1F - b / 8F) * 0.45F : 0F;
            bands[b] = (float)Math.min(1.0, shape * (0.45 + 0.55 * wave) + bass);
        }
        return new Spectrum(positionMs, bands, 0.6F + 0.3F * kick, positionMs / 500L, 0.75F);
    }

    @Override
    public void setVolume(float volume) {
        // Nothing to make louder.
    }
}
