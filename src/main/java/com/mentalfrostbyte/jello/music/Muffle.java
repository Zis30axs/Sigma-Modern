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

    Muffle(int channels, float startAmount) {
        this.x1 = new double[channels];
        this.x2 = new double[channels];
        this.y1 = new double[channels];
        this.y2 = new double[channels];
        this.amount = startAmount;
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
            java.util.Arrays.fill(this.x1, 0.0);
            java.util.Arrays.fill(this.x2, 0.0);
            java.util.Arrays.fill(this.y1, 0.0);
            java.util.Arrays.fill(this.y2, 0.0);
            return;
        }
        double cutoff = Math.exp(Math.log(OPEN_HZ) + (Math.log(UNDER_HZ) - Math.log(OPEN_HZ)) * this.amount);
        cutoff = Math.min(cutoff, sampleRate * 0.45);
        // RBJ cookbook low-pass, Q = 1/sqrt(2).
        double w0 = 2.0 * Math.PI * cutoff / sampleRate, cos = Math.cos(w0), alpha = Math.sin(w0) / (2.0 * Math.sqrt(0.5));
        double a0 = 1.0 + alpha;
        double b0 = (1.0 - cos) / 2.0 / a0, b1 = (1.0 - cos) / a0, b2 = b0, a1 = -2.0 * cos / a0, a2 = (1.0 - alpha) / a0;
        double gain = 1.0 - 0.3 * this.amount;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                int i = f * channels + c;
                double x = samples[i];
                double y = b0 * x + b1 * this.x1[c] + b2 * this.x2[c] - a1 * this.y1[c] - a2 * this.y2[c];
                // Flush denormals: a decaying tail must not slow the decoder to a crawl.
                if (Math.abs(y) < 1e-20) y = 0.0;
                this.x2[c] = this.x1[c];
                this.x1[c] = x;
                this.y2[c] = this.y1[c];
                this.y1[c] = y;
                samples[i] = (float)(y * gain);
            }
        }
    }
}
