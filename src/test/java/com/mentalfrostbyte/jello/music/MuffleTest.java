package com.mentalfrostbyte.jello.music;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The under-water filter: untouched when open, highs gone and lows kept when under, never unstable. */
class MuffleTest {
    private static final float RATE = 44_100F;

    /** Runs {@code seconds} of a mono sine through {@code muffle}; returns the peak of the last tenth of a second. */
    private static float peakAfter(Muffle muffle, double hz, double seconds, float target) {
        int block = 1152;
        float[] samples = new float[block];
        float peak = 0F;
        long total = Math.round(seconds * RATE), tail = Math.round(0.1 * RATE);
        for (long start = 0; start < total; start += block) {
            for (int i = 0; i < block; i++) samples[i] = (float)Math.sin(2 * Math.PI * hz * (start + i) / RATE);
            muffle.process(samples, block, 1, RATE, target);
            for (int i = 0; i < block; i++) {
                assertTrue(Float.isFinite(samples[i]));
                if (start + i >= total - tail) peak = Math.max(peak, Math.abs(samples[i]));
            }
        }
        return peak;
    }

    @Test
    void openPassesAudioUntouched() {
        Muffle muffle = new Muffle(2, 0F);
        float[] samples = {0.1F, -0.2F, 0.3F, -0.4F, 0.5F, -0.6F};
        float[] copy = samples.clone();
        muffle.process(samples, samples.length, 2, RATE, 0F);
        assertArrayEquals(copy, samples);
    }

    @Test
    void underWaterCutsTheHighsAndKeepsTheLows() {
        float high = peakAfter(new Muffle(1, 0F), 8000, 1.5, 1F);
        float low = peakAfter(new Muffle(1, 0F), 100, 1.5, 1F);
        assertTrue(high < 0.03F, "8 kHz under water: " + high);
        assertTrue(low > 0.6F && low < 0.75F, "100 Hz under water keeps its level, less the 30% dip: " + low);
    }

    @Test
    void surfacingOpensItUpAgain() {
        Muffle muffle = new Muffle(1, 1F);
        peakAfter(muffle, 8000, 1.0, 1F);
        float after = peakAfter(muffle, 8000, 1.5, 0F);
        assertTrue(after > 0.95F, "8 kHz after surfacing: " + after);
    }
}
