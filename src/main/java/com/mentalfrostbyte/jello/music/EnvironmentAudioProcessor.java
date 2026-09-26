package com.mentalfrostbyte.jello.music;

import java.util.Arrays;

/** Per-session PCM effects. All state belongs to the decoding thread; no game calls or per-block buffers. */
final class EnvironmentAudioProcessor {
    private static final double OPEN_LOG = Math.log(20_000.0);
    private static final double WATER_LOG = Math.log(650.0) - OPEN_LOG, LAVA_LOG = Math.log(400.0) - OPEN_LOG;
    private static final double RAIN_LOG = Math.log(14_000.0) - OPEN_LOG, SNOW_LOG = Math.log(10_000.0) - OPEN_LOG;
    private final int channels;
    private final float sampleRate;
    private final Muffle filter;
    private final RoomReverb reverb;
    private float water, lava, rain, snow, mix, decay;

    EnvironmentAudioProcessor(int channels, float sampleRate, MusicEffects.AudioTarget start) {
        if (channels < 1 || channels > 2 || !Float.isFinite(sampleRate) || sampleRate < 8_000F || sampleRate > 192_000F) {
            throw new IllegalArgumentException("Unsupported PCM format");
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.filter = new Muffle(channels, start.water() + start.lava() + start.rain() + start.snow());
        this.reverb = new RoomReverb(channels, sampleRate);
        setAmounts(start);
    }

    void reset(MusicEffects.AudioTarget start) {
        this.filter.reset();
        this.reverb.reset();
        setAmounts(start);
    }

    private void setAmounts(MusicEffects.AudioTarget target) {
        this.water = target.water();
        this.lava = target.lava();
        this.rain = target.rain();
        this.snow = target.snow();
        this.mix = target.reverbMix();
        this.decay = target.reverbSeconds();
    }

    void process(float[] samples, int count, MusicEffects.AudioTarget target) {
        if (count < 0 || count > samples.length || count % this.channels != 0) {
            throw new IllegalArgumentException("Invalid PCM sample count");
        }
        if (count == 0) return;
        double seconds = count / (double)this.channels / this.sampleRate;
        float quick = (float)(1.0 - Math.exp(-seconds / 0.18));
        float slow = (float)(1.0 - Math.exp(-seconds / 0.8));
        this.water = glide(this.water, target.water(), quick);
        this.lava = glide(this.lava, target.lava(), slow);
        this.rain = glide(this.rain, target.rain(), slow);
        this.snow = glide(this.snow, target.snow(), slow);
        float oldMix = this.mix;
        this.mix = glide(this.mix, target.reverbMix(), slow);
        this.decay += (target.reverbSeconds() - this.decay) * slow;

        float total = this.water + this.lava + this.rain + this.snow;
        if (total == 0F) {
            this.filter.reset();
        } else {
            // Crossfading between scene families must not stack several full low-passes or volume cuts.
            double norm = Math.max(1.0, total);
            double cutoff = Math.exp(OPEN_LOG + (WATER_LOG * this.water + LAVA_LOG * this.lava
                + RAIN_LOG * this.rain + SNOW_LOG * this.snow) / norm);
            double gain = 1.0 - (0.3 * this.water + 0.4 * this.lava) / Math.max(1F, this.water + this.lava);
            // Fade the last small amount to true bypass, including at low sample rates near Nyquist.
            double blend = Math.min(1.0, total / 0.05);
            this.filter.filter(samples, count, this.channels, this.sampleRate, cutoff, gain, blend);
        }
        if (this.mix == 0F && oldMix == 0F) {
            this.reverb.resetIfActive();
        } else {
            this.reverb.process(samples, count, oldMix, this.mix, this.decay);
        }
    }

    private static float glide(float value, float target, float factor) {
        float next = value + (target - value) * factor;
        return Math.abs(next - target) < 0.00001F ? target : next;
    }

    /** Four damped, normalized feedback delay lines per channel; fixed lengths avoid pitch modulation. */
    private static final class RoomReverb {
        private static final double[] DELAYS = {0.0297, 0.0371, 0.0411, 0.0437};
        private final float[][][] lines;
        private final int[][] positions;
        private final float[][] damped;
        private final double[][] feedback;
        private final int channels;
        private final float rate;
        private final float damping;
        private boolean active;

        RoomReverb(int channels, float rate) {
            this.channels = channels;
            this.rate = rate;
            this.damping = (float)Math.exp(-2.0 * Math.PI * 6_000.0 / rate);
            this.lines = new float[channels][DELAYS.length][];
            this.positions = new int[channels][DELAYS.length];
            this.damped = new float[channels][DELAYS.length];
            this.feedback = new double[channels][DELAYS.length];
            for (int c = 0; c < channels; c++) {
                for (int d = 0; d < DELAYS.length; d++) {
                    this.lines[c][d] = new float[Math.max(1, (int)Math.round((DELAYS[d] + c * 0.0013) * rate))];
                }
            }
        }

        void process(float[] samples, int count, float fromMix, float toMix, float seconds) {
            this.active = true;
            int frames = count / this.channels;
            for (int c = 0; c < this.channels; c++) {
                for (int d = 0; d < DELAYS.length; d++) {
                    // -60 dB after the requested decay time; feedback stays strictly below unity.
                    this.feedback[c][d] = Math.min(0.9, Math.pow(0.001, this.lines[c][d].length / (this.rate * seconds)));
                }
            }
            for (int f = 0; f < frames; f++) {
                float wet = fromMix + (toMix - fromMix) * (f + 1F) / frames;
                for (int c = 0; c < this.channels; c++) {
                    int i = f * this.channels + c;
                    float dry = samples[i], reflected = 0F;
                    for (int d = 0; d < DELAYS.length; d++) {
                        float[] line = this.lines[c][d];
                        int at = this.positions[c][d];
                        float delayed = line[at];
                        float damp = delayed + this.damping * (this.damped[c][d] - delayed);
                        if (Math.abs(damp) < 1e-20F) damp = 0F;
                        this.damped[c][d] = damp;
                        double feedback = this.feedback[c][d];
                        // Normalized injection keeps each delay's worst-case gain <= 1.
                        line[at] = (float)(dry * (1.0 - feedback) + damp * feedback);
                        this.positions[c][d] = at + 1 == line.length ? 0 : at + 1;
                        reflected += delayed / DELAYS.length;
                    }
                    samples[i] = dry * (1F - wet) + reflected * wet;
                }
            }
        }

        void resetIfActive() {
            if (this.active) reset();
        }

        void reset() {
            for (int c = 0; c < this.channels; c++) {
                for (float[] line : this.lines[c]) Arrays.fill(line, 0F);
                Arrays.fill(this.positions[c], 0);
                Arrays.fill(this.damped[c], 0F);
            }
            this.active = false;
        }
    }
}
