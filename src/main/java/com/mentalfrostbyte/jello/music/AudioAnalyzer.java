package com.mentalfrostbyte.jello.music;

import org.jspecify.annotations.Nullable;

/**
 * Turns decoded audio into {@link Spectrum} snapshots for visuals: every {@value #HOP} samples it takes the last
 * {@value #SIZE} (Hann-windowed, mixed to mono) through an FFT, folds the result into {@value #BANDS} log-spaced
 * bands and checks the bass for a beat - a hop whose 40-160 Hz energy stands well above the last second's average.
 *
 * <p>Fed by the decoding thread, read by the render thread. Snapshots are immutable and published through a
 * fixed ring, each stamped with where in the track it was heard; {@link #at} finds the one for the position the
 * listener is at - not the decoder, which runs ahead by the audio line's buffer. The work arrays are reused, so a
 * hop costs one 2048-point FFT and no garbage beyond the snapshot itself.</p>
 */
public final class AudioAnalyzer {
    public static final int BANDS = 48;
    static final int SIZE = 2048, HOP = 1024;
    private static final int RING = 256, HISTORY = 43;
    private static final float LOW_HZ = 50F, HIGH_HZ = 15_000F;
    private static final float BEAT_RATIO = 1.45F, BEAT_GAP_MS = 280F;

    private final float sampleRate;
    private final float[] window = new float[SIZE], samples = new float[SIZE], re = new float[SIZE], im = new float[SIZE];
    private final int[] bandLo = new int[BANDS], bandHi = new int[BANDS];
    private final float[] bandWeight = new float[BANDS];
    private final int bassLo, bassHi;
    private int write, filled, sinceHop;
    private float peak = 1e-4F, levelPeak = 1e-4F;
    private final float[] history = new float[HISTORY];
    private int historyCount, historyAt;
    private long beats;
    private float beatStrength;
    private double lastBeatMs = -1e9;
    private final Spectrum[] ring = new Spectrum[RING];
    private volatile int published;

    public AudioAnalyzer(float sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < SIZE; i++) this.window[i] = 0.5F - 0.5F * (float)Math.cos(2.0 * Math.PI * i / (SIZE - 1));
        float binHz = sampleRate / SIZE;
        for (int b = 0; b < BANDS; b++) {
            float lo = LOW_HZ * (float)Math.pow(HIGH_HZ / LOW_HZ, b / (float)BANDS);
            float hi = LOW_HZ * (float)Math.pow(HIGH_HZ / LOW_HZ, (b + 1) / (float)BANDS);
            this.bandLo[b] = Math.max(1, Math.min(SIZE / 2 - 1, Math.round(lo / binHz)));
            this.bandHi[b] = Math.max(this.bandLo[b], Math.min(SIZE / 2 - 1, Math.round(hi / binHz)));
            // Music is quieter up high (about -3 dB an octave); lift the highs so the bars read evenly.
            this.bandWeight[b] = (float)Math.sqrt(Math.max(1F, (lo + hi) / 2F / 200F));
        }
        this.bassLo = Math.max(1, Math.round(40F / binHz));
        this.bassHi = Math.max(this.bassLo, Math.round(160F / binHz));
    }

    /**
     * Feeds {@code count} interleaved samples (-1..1) of {@code channels} channels, the first of them heard at
     * {@code startMs} into the track.
     */
    public void feed(float[] interleaved, int count, int channels, double startMs) {
        int frames = count / Math.max(1, channels);
        for (int f = 0; f < frames; f++) {
            float mono = 0F;
            for (int c = 0; c < channels; c++) mono += interleaved[f * channels + c];
            this.samples[this.write] = mono / channels;
            this.write = (this.write + 1) % SIZE;
            if (this.filled < SIZE) this.filled++;
            if (++this.sinceHop >= HOP && this.filled >= SIZE) {
                this.sinceHop = 0;
                analyse(startMs + (f + 1) * 1000.0 / this.sampleRate);
            }
        }
    }

    private void analyse(double timeMs) {
        for (int i = 0; i < SIZE; i++) {
            this.re[i] = this.samples[(this.write + i) % SIZE] * this.window[i];
            this.im[i] = 0F;
        }
        fft(this.re, this.im);

        float[] bands = new float[BANDS];
        float loudest = 0F;
        for (int b = 0; b < BANDS; b++) {
            float max = 0F;
            for (int k = this.bandLo[b]; k <= this.bandHi[b]; k++) max = Math.max(max, magnitude(k));
            bands[b] = max * this.bandWeight[b];
            loudest = Math.max(loudest, bands[b]);
        }
        // A slowly falling reference for "loud", so quiet and loud tracks both fill the bars.
        this.peak = Math.max(loudest, Math.max(1e-4F, this.peak * 0.996F));
        for (int b = 0; b < BANDS; b++) {
            double db = 20.0 * Math.log10(Math.max(bands[b], 1e-9F) / this.peak);
            bands[b] = (float)Math.max(0.0, Math.min(1.0, 1.0 + db / 48.0));
        }

        float energy = 0F;
        for (int i = 0; i < SIZE; i++) energy += this.re[i] * this.re[i] + this.im[i] * this.im[i];
        float rms = (float)Math.sqrt(energy) / SIZE;
        this.levelPeak = Math.max(rms, Math.max(1e-4F, this.levelPeak * 0.997F));
        float level = Math.min(1F, rms / this.levelPeak);

        float bass = 0F;
        for (int k = this.bassLo; k <= this.bassHi; k++) {
            float m = magnitude(k);
            bass += m * m;
        }
        if (this.historyCount >= 10) {
            float average = 0F;
            for (int i = 0; i < this.historyCount; i++) average += this.history[i];
            average /= this.historyCount;
            if (average > 1e-9F && bass > average * BEAT_RATIO && timeMs - this.lastBeatMs >= BEAT_GAP_MS) {
                this.beats++;
                this.beatStrength = Math.min(1F, 0.3F + (bass / average - BEAT_RATIO) / 2F);
                this.lastBeatMs = timeMs;
            }
        }
        this.history[this.historyAt] = bass;
        this.historyAt = (this.historyAt + 1) % HISTORY;
        this.historyCount = Math.min(HISTORY, this.historyCount + 1);

        int n = this.published;
        this.ring[n % RING] = new Spectrum(Math.round(timeMs), bands, level, this.beats, this.beatStrength);
        this.published = n + 1;
    }

    private float magnitude(int k) {
        return (float)Math.sqrt(this.re[k] * this.re[k] + this.im[k] * this.im[k]) * (2F / SIZE);
    }

    /** The latest snapshot heard at or before {@code positionMs}, or {@code null} when there is none yet. */
    public @Nullable Spectrum at(long positionMs) {
        int n = this.published;
        for (int i = n - 1; i >= Math.max(0, n - RING); i--) {
            Spectrum s = this.ring[i % RING];
            if (s != null && s.timeMs() <= positionMs) return s;
        }
        return null;
    }

    /** Forgets everything heard (a seek): nothing from before it may be shown after it. */
    public void reset() {
        for (int i = 0; i < RING; i++) this.ring[i] = null;
        this.published = 0;
        this.filled = 0;
        this.sinceHop = 0;
        this.historyCount = 0;
        this.lastBeatMs = -1e9;
    }

    /** In-place iterative radix-2 FFT; {@code re.length} must be a power of two. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            float wr = (float)Math.cos(angle), wi = (float)Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float cr = 1F, ci = 0F;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
