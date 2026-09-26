package com.mentalfrostbyte.jello.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The spectrum and beat analysis on synthetic audio, fed the way the decoder feeds it (1152-frame stereo blocks). */
class AudioAnalyzerTest {
    private static final int RATE = 44_100, BLOCK = 1152;

    private interface Signal {
        float at(double seconds);
    }

    /** Feeds {@code seconds} of {@code signal} (the same on both channels), starting {@code fromMs} into the track. */
    private static void feed(AudioAnalyzer analyzer, Signal signal, double fromMs, double seconds) {
        float[] block = new float[BLOCK * 2];
        long frames = Math.round(seconds * RATE);
        for (long start = 0; start < frames; start += BLOCK) {
            for (int f = 0; f < BLOCK; f++) {
                float v = signal.at(fromMs / 1000.0 + (start + f) / (double)RATE);
                block[f * 2] = v;
                block[f * 2 + 1] = v;
            }
            analyzer.feed(block, block.length, 2, fromMs + start * 1000.0 / RATE);
        }
    }

    @Test
    void aSineLightsTheBandItIsIn() {
        AudioAnalyzer analyzer = new AudioAnalyzer(RATE);
        feed(analyzer, t -> 0.5F * (float)Math.sin(2 * Math.PI * 1000 * t), 0, 1.0);
        Spectrum s = analyzer.at(1000);
        assertNotNull(s);
        int loudest = 0;
        for (int b = 1; b < AudioAnalyzer.BANDS; b++) if (s.bands()[b] > s.bands()[loudest]) loudest = b;
        // The band edges are log-spaced from 50 Hz to 15 kHz: 1 kHz sits about halfway up.
        double expected = Math.log(1000.0 / 50.0) / Math.log(15000.0 / 50.0) * AudioAnalyzer.BANDS;
        assertTrue(Math.abs(loudest - expected) <= 1.5, "loudest band " + loudest + ", expected about " + expected);
        assertEquals(1F, s.bands()[loudest], 0.05F);
        assertTrue(s.bands()[2] < 0.4F, "a band far from the tone stays low");
    }

    @Test
    void aKickEveryHalfSecondIsTwoBeatsASecond() {
        AudioAnalyzer analyzer = new AudioAnalyzer(RATE);
        feed(analyzer, t -> {
            double since = t % 0.5;
            float kick = since < 0.12 ? (float)(Math.sin(2 * Math.PI * 70 * since) * Math.exp(-since * 25)) : 0F;
            return 0.8F * kick + 0.05F * (float)Math.sin(2 * Math.PI * 2000 * t);
        }, 0, 6.0);
        Spectrum s = analyzer.at(6000);
        assertNotNull(s);
        assertTrue(s.beats() >= 10 && s.beats() <= 12, "beats in 6 s at 120 BPM: " + s.beats());
    }

    @Test
    void aSteadyToneHasNoBeats() {
        AudioAnalyzer analyzer = new AudioAnalyzer(RATE);
        feed(analyzer, t -> 0.6F * (float)Math.sin(2 * Math.PI * 70 * t), 0, 4.0);
        assertEquals(0L, analyzer.at(4000).beats());
    }

    @Test
    void findsWhatWasHeardAtAPositionAndNothingStaleAfterASeek() {
        AudioAnalyzer analyzer = new AudioAnalyzer(RATE);
        feed(analyzer, t -> 0.3F * (float)Math.sin(2 * Math.PI * 440 * t), 0, 2.0);
        Spectrum at = analyzer.at(1000);
        assertNotNull(at);
        assertTrue(at.timeMs() <= 1000 && at.timeMs() > 1000 - 50, "snapshot at " + at.timeMs());
        assertNull(analyzer.at(10), "nothing is known before the first full window");

        analyzer.reset();
        assertNull(analyzer.at(2000));
        feed(analyzer, t -> 0.3F * (float)Math.sin(2 * Math.PI * 440 * t), 60_000, 0.5);
        assertNull(analyzer.at(1500), "nothing from before the seek");
        assertNotNull(analyzer.at(60_500));
    }
}
