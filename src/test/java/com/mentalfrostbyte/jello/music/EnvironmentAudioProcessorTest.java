package com.mentalfrostbyte.jello.music;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real PCM signals through the production processor, without a device, decoder or game instance. */
class EnvironmentAudioProcessorTest {
    private static final MusicEffects.AudioTarget DRY = MusicEffects.AudioTarget.DRY;
    private static final MusicEffects.AudioTarget WATER = new MusicEffects.AudioTarget(1F, 0F, 0F, 0F, 0F, 0.3F);
    private static final MusicEffects.AudioTarget LAVA = new MusicEffects.AudioTarget(0F, 1F, 0F, 0F, 0F, 0.3F);
    private static final MusicEffects.AudioTarget ROOM = new MusicEffects.AudioTarget(0F, 0F, 0F, 0F, 0.1F, 1.2F);

    @ParameterizedTest(name = "{0} Hz, {1} channels")
    @CsvSource({"22050,1", "22050,2", "44100,1", "44100,2", "48000,1", "48000,2"})
    @DisplayName("Dry audio passes through exactly, including unused buffer capacity")
    void dryPassesThroughExactly(float rate, int channels) {
        EnvironmentAudioProcessor processor = new EnvironmentAudioProcessor(channels, rate, DRY);
        float[] pcm = {0.1F, -0.5F, 0.0F, -0.0F, 0.99F, -0.99F, 123F, 456F};
        float[] original = pcm.clone();
        processor.process(pcm, 6, DRY);
        assertArrayEquals(original, pcm);
    }

    @ParameterizedTest(name = "{0} Hz")
    @CsvSource({"22050", "44100", "48000"})
    @DisplayName("Water keeps its original frequency response and lava is darker")
    void liquidFrequencyResponse(float rate) {
        assertEquals(0.7, rmsGain(rate, 100, WATER), 0.015);
        assertEquals(0.6, rmsGain(rate, 100, LAVA), 0.015);
        double waterHigh = rmsGain(rate, 8000, WATER);
        double lavaHigh = rmsGain(rate, 8000, LAVA);
        assertTrue(waterHigh < 0.03, "water must cut high frequencies");
        assertTrue(lavaHigh < waterHigh, "lava must be darker than water");
    }

    @Test
    @DisplayName("Weather softens the top end while preserving low frequencies")
    void weatherKeepsLowsAndSnowIsSofterThanRain() {
        MusicEffects.AudioTarget rain = new MusicEffects.AudioTarget(0F, 0F, 1F, 0F, 0F, 0.3F);
        MusicEffects.AudioTarget snow = new MusicEffects.AudioTarget(0F, 0F, 0F, 1F, 0F, 0.3F);
        assertEquals(1.0, rmsGain(48000, 100, rain), 0.01);
        assertEquals(1.0, rmsGain(48000, 100, snow), 0.01);
        double rainy = rmsGain(48000, 10000, rain), snowy = rmsGain(48000, 10000, snow);
        assertTrue(rainy > 0.8 && rainy < 1.0);
        assertTrue(snowy > 0.6 && snowy < rainy);
    }

    @ParameterizedTest(name = "{0} Hz, {1} channels")
    @CsvSource({"22050,1", "44100,2", "48000,2"})
    @DisplayName("Room impulse produces a bounded decaying tail with isolated channels")
    void roomTailDecays(float rate, int channels) {
        EnvironmentAudioProcessor processor = new EnvironmentAudioProcessor(channels, rate, ROOM);
        float[] impulse = new float[Math.round(rate * 3) * channels];
        impulse[0] = 1F;
        processor.process(impulse, impulse.length, ROOM);
        double early = 0, late = 0;
        for (int f = 1; f < impulse.length / channels; f++) {
            float sample = impulse[f * channels];
            assertTrue(Float.isFinite(sample) && Math.abs(sample) <= 1F);
            if (f < rate / 2) early += sample * sample;
            if (f > rate * 2) late += sample * sample;
            if (channels == 2) assertEquals(0F, impulse[f * channels + 1], "silent channel stays silent");
        }
        assertTrue(early > 1e-7, "a room must produce an audible tail after the impulse");
        assertTrue(late < early * 1e-5, "tail must decay instead of feeding back forever");
    }

    @Test
    @DisplayName("Seek reset discards both filter history and room tail")
    void resetClearsPreviousSong() {
        for (MusicEffects.AudioTarget target : new MusicEffects.AudioTarget[]{ROOM, WATER, LAVA}) {
            EnvironmentAudioProcessor processor = new EnvironmentAudioProcessor(2, 44100, target);
            float[] pcm = new float[2304];
            Arrays.fill(pcm, 0.7F);
            processor.process(pcm, pcm.length, target);
            processor.reset(target);
            Arrays.fill(pcm, 0F);
            for (int i = 0; i < 20; i++) processor.process(pcm, pcm.length, target);
            assertArrayEquals(new float[pcm.length], pcm);
        }
    }

    @ParameterizedTest(name = "{0} Hz, {1} channels")
    @CsvSource({"22050,1", "44100,2", "48000,2"})
    @DisplayName("Switching all effects off settles to exact bypass and does not resurrect the tail")
    void disableSettlesToTrueBypass(float rate, int channels) {
        EnvironmentAudioProcessor processor = new EnvironmentAudioProcessor(channels, rate, ROOM);
        float[] block = new float[512 * channels];
        Arrays.fill(block, 0.5F);
        processor.process(block, block.length, ROOM);
        for (int i = 0; i < Math.ceil(rate * 10 / 512); i++) {
            Arrays.fill(block, 0.25F);
            processor.process(block, block.length, DRY);
        }
        float[] expected = new float[block.length];
        Arrays.fill(expected, 0.25F);
        assertArrayEquals(expected, block);
        Arrays.fill(block, 0F);
        processor.process(block, block.length, ROOM);
        assertArrayEquals(new float[block.length], block, "old room must not reappear after bypass");
    }

    @ParameterizedTest(name = "{0} Hz, {1} channels")
    @CsvSource({"22050,1", "44100,2", "48000,2"})
    @DisplayName("Rapid scene changes stay finite and bounded")
    void rapidChangesStayStable(float rate, int channels) {
        MusicEffects.AudioTarget[] scenes = {WATER, LAVA, ROOM, DRY,
            new MusicEffects.AudioTarget(0F, 0F, 1F, 0F, 0.1F, 0.3F)};
        EnvironmentAudioProcessor processor = new EnvironmentAudioProcessor(channels, rate, DRY);
        float[] block = new float[256 * channels];
        for (int b = 0; b < 1000; b++) {
            for (int i = 0; i < block.length; i++) block[i] = (i / channels + b * 256) % 97 < 48 ? 0.6F : -0.6F;
            processor.process(block, block.length, scenes[b % scenes.length]);
            for (float sample : block) assertTrue(Float.isFinite(sample) && Math.abs(sample) < 1.5F);
        }
    }

    @Test
    @DisplayName("Scene changes ramp gain across block boundaries")
    void switchingDoesNotIntroduceGainSteps() {
        EnvironmentAudioProcessor processor = new EnvironmentAudioProcessor(1, 44100, DRY);
        float[] block = new float[1152];
        float previous = 0.5F;
        double largestBoundaryStep = 0;
        for (int b = 0; b < 400; b++) {
            Arrays.fill(block, 0.5F);
            MusicEffects.AudioTarget target = b < 50 ? WATER : b < 100 ? LAVA : b < 200 ? ROOM : DRY;
            processor.process(block, block.length, target);
            largestBoundaryStep = Math.max(largestBoundaryStep, Math.abs(block[0] - previous));
            previous = block[block.length - 1];
        }
        assertTrue(largestBoundaryStep < 0.01, "block boundary step: " + largestBoundaryStep);
    }

    private static double rmsGain(float rate, double hz, MusicEffects.AudioTarget target) {
        EnvironmentAudioProcessor processor = new EnvironmentAudioProcessor(1, rate, target);
        float[] block = new float[512];
        double energyIn = 0, energyOut = 0;
        for (int start = 0; start < rate; start += block.length) {
            double blockEnergy = 0;
            for (int i = 0; i < block.length; i++) {
                block[i] = (float)(0.5 * Math.sin(2 * Math.PI * hz * (start + i) / rate));
                blockEnergy += block[i] * block[i];
            }
            processor.process(block, block.length, target);
            if (start > rate / 2) {
                energyIn += blockEnergy;
                for (float sample : block) energyOut += sample * sample;
            }
        }
        return Math.sqrt(energyOut / energyIn);
    }
}
