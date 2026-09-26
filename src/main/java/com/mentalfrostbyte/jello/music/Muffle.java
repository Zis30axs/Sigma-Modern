package com.mentalfrostbyte.jello.music;

/**
 * The "under water" sound: a low-pass filter (a biquad per channel) with a slight drop in level, faded in and out
 * rather than switched, so going under or surfacing never clicks. {@link #process} glides its amount towards the
 * target a little every block and derives the cutoff from it - 20 kHz (open) down to about 650 Hz (fully under) on
 * a log scale, which sounds like a steady descent into the water.
 */
final class Muffle {
    private static final double OPEN_HZ = 20_000.0, UNDER_HZ = 650.0, GLIDE_SECONDS = 0.18;

    private final double[] x1, x2, y1, y2;
    private float amount;
    private boolean initialized;
    private double b0, b1, b2, a1, a2;
    private double lastGain = 1.0, lastBlend;

    Muffle(int channels, float startAmount) {
        this.x1 = new double[channels];
        this.x2 = new double[channels];
        this.y1 = new double[channels];
        this.y2 = new double[channels];
        this.amount = startAmount;
        this.lastBlend = startAmount > 0F ? 1.0 : 0.0;
    }

    float amount() {
        return this.amount;
    }

    /** Filters {@code count} interleaved samples in place, heading for {@code target} (0 open .. 1 under). */
    void process(float[] samples, int count, int channels, float sampleRate, float target) {
        int frames = count / Math.max(1, channels);
        double seconds = frames / (double)sampleRate;
        this.amount += (float)((target - this.amount) * (1.0 - Math.exp(-seconds / GLIDE_SECONDS)));
        if (target == 0F && this.amount < 0.002F) {
            // Fully open: pass the audio through untouched, and start from rest next time.
            this.amount = 0F;
            reset();
            return;
        }
        double cutoff = Math.exp(Math.log(OPEN_HZ) + (Math.log(UNDER_HZ) - Math.log(OPEN_HZ)) * this.amount);
        filter(samples, count, channels, sampleRate, cutoff, 1.0 - 0.3 * this.amount, 1.0);
    }

    /** Parameterized low-pass. The caller smooths the target; coefficients and wet gain ramp within each block. */
    void filter(float[] samples, int count, int channels, float sampleRate, double cutoff, double gain, double blend) {
        int frames = count / channels;
        if (frames == 0) return;
        cutoff = Math.min(cutoff, sampleRate * 0.45);
        // RBJ cookbook low-pass, Q = 1/sqrt(2).
        double w0 = 2.0 * Math.PI * cutoff / sampleRate, cos = Math.cos(w0), alpha = Math.sin(w0) / (2.0 * Math.sqrt(0.5));
        double a0 = 1.0 + alpha;
        double b0 = (1.0 - cos) / 2.0 / a0, b1 = (1.0 - cos) / a0, b2 = b0, a1 = -2.0 * cos / a0, a2 = (1.0 - alpha) / a0;
        if (!this.initialized) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
            this.initialized = true;
        }
        for (int f = 0; f < frames; f++) {
            double t = (f + 1.0) / frames;
            double cb0 = this.b0 + (b0 - this.b0) * t, cb1 = this.b1 + (b1 - this.b1) * t;
            double cb2 = this.b2 + (b2 - this.b2) * t, ca1 = this.a1 + (a1 - this.a1) * t;
            double ca2 = this.a2 + (a2 - this.a2) * t;
            double level = this.lastGain + (gain - this.lastGain) * t;
            double wet = this.lastBlend + (blend - this.lastBlend) * t;
            for (int c = 0; c < channels; c++) {
                int i = f * channels + c;
                double x = samples[i];
                double y = cb0 * x + cb1 * this.x1[c] + cb2 * this.x2[c] - ca1 * this.y1[c] - ca2 * this.y2[c];
                // Flush denormals: a decaying tail must not slow the decoder to a crawl.
                if (Math.abs(y) < 1e-20) y = 0.0;
                this.x2[c] = this.x1[c];
                this.x1[c] = x;
                this.y2[c] = this.y1[c];
                this.y1[c] = y;
                samples[i] = (float)(x + (y * level - x) * wet);
            }
        }
        this.b0 = b0;
        this.b1 = b1;
        this.b2 = b2;
        this.a1 = a1;
        this.a2 = a2;
        this.lastGain = gain;
        this.lastBlend = blend;
    }

    /** Called on seeks and when completely bypassed, always on the decoding thread. */
    void reset() {
        java.util.Arrays.fill(this.x1, 0.0);
        java.util.Arrays.fill(this.x2, 0.0);
        java.util.Arrays.fill(this.y1, 0.0);
        java.util.Arrays.fill(this.y2, 0.0);
        this.initialized = false;
        this.lastGain = 1.0;
        this.lastBlend = 0.0;
    }
}
