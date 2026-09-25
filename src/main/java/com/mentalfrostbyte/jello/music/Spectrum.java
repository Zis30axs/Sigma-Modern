package com.mentalfrostbyte.jello.music;

/**
 * What the music sounds like at one moment, for visuals: {@code bands} are 0..1 loudness per frequency band, low
 * to high (log-spaced), relative to the track's recent peak; {@code level} is the overall loudness, 0..1.
 * {@code beats} counts the beats heard so far in the track - it only ever grows, so a display that samples it at its
 * own frame rate still sees every beat exactly once - and {@code beatStrength} is how hard the latest one hit, 0..1.
 *
 * @param timeMs where in the track this was heard
 */
public record Spectrum(long timeMs, float[] bands, float level, long beats, float beatStrength) {}
