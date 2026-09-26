package com.mentalfrostbyte.jello.music;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MusicEffectsTest {
    @Test
    @DisplayName("No world or a cleared environment produces the exact dry target")
    void neutralEnvironmentClearsEveryAudioEffect() {
        MusicEffects effects = new MusicEffects();
        assertEquals(MusicEffects.AudioTarget.DRY, effects.audioTarget());
        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.LAVA, 1F, 1F, 1F, 1F));
        effects.setEnvironment(MusicEnvironment.NEUTRAL);
        assertEquals(MusicEffects.AudioTarget.DRY, effects.audioTarget());
    }

    @ParameterizedTest
    @EnumSource(value = MusicEnvironment.Liquid.class, names = {"WATER", "LAVA"})
    @DisplayName("An enabled liquid suppresses weather and room reflections")
    void enabledLiquidHasPriorityOverWeatherAndRoom(MusicEnvironment.Liquid liquid) {
        MusicEffects effects = new MusicEffects();
        effects.setEnvironment(new MusicEnvironment(liquid, 1F, 1F, 1F, 1F));
        MusicEffects.AudioTarget target = effects.audioTarget();
        assertAll(
            () -> assertEquals(liquid == MusicEnvironment.Liquid.WATER ? 1F : 0F, target.water()),
            () -> assertEquals(liquid == MusicEnvironment.Liquid.LAVA ? 1F : 0F, target.lava()),
            () -> assertEquals(0F, target.rain()),
            () -> assertEquals(0F, target.snow()),
            () -> assertEquals(0F, target.reverbMix())
        );
    }

    @Test
    @DisplayName("Disabling one liquid keeps the other active and permits ordinary environment effects")
    void liquidSwitchesAreIndependent() {
        MusicEffects effects = new MusicEffects();
        effects.setUnderwaterSound(false);
        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.WATER, 0.5F, 0F, 0.5F, 0F));
        MusicEffects.AudioTarget waterDisabled = effects.audioTarget();
        assertEquals(0F, waterDisabled.water());
        assertEquals(0.5F, waterDisabled.rain());
        assertTrue(waterDisabled.reverbMix() > 0F);

        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.LAVA, 0.5F, 0F, 0.5F, 0F));
        assertEquals(1F, effects.audioTarget().lava());
        effects.setLavaSound(false);
        assertEquals(0.5F, effects.audioTarget().rain());
        assertEquals(0F, effects.audioTarget().lava());

        effects.setUnderwaterSound(true);
        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.WATER, 0.5F, 0F, 0.5F, 0F));
        assertEquals(1F, effects.audioTarget().water());
    }

    @Test
    @DisplayName("Weather color and gentle room reflections can coexist and be disabled independently")
    void weatherAndRoomCombineWithoutDependingOnEachOthersSwitch() {
        MusicEffects effects = new MusicEffects();
        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.NONE, 0.6F, 0.3F, 0.8F, 0.5F));
        MusicEffects.AudioTarget combined = effects.audioTarget();
        assertEquals(0.6F, combined.rain());
        assertEquals(0.3F, combined.snow());
        assertTrue(combined.reverbMix() > 0F && combined.reverbMix() <= 0.1F);

        effects.setWeatherSound(false);
        MusicEffects.AudioTarget roomOnly = effects.audioTarget();
        assertEquals(0F, roomOnly.rain());
        assertEquals(0F, roomOnly.snow());
        assertEquals(combined.reverbMix(), roomOnly.reverbMix());

        effects.setWeatherSound(true);
        effects.setSpaceSound(false);
        MusicEffects.AudioTarget weatherOnly = effects.audioTarget();
        assertEquals(combined.rain(), weatherOnly.rain());
        assertEquals(combined.snow(), weatherOnly.snow());
        assertEquals(0F, weatherOnly.reverbMix());
    }

    @Test
    @DisplayName("Strength reduces audible effect weights without changing the room size")
    void strengthScalesEffectsAndZeroIsExactlyDry() {
        MusicEffects effects = new MusicEffects();
        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.NONE, 0.8F, 0.6F, 1F, 1F));
        MusicEffects.AudioTarget full = effects.audioTarget();
        effects.setEnvironmentStrength(0.5F);
        MusicEffects.AudioTarget half = effects.audioTarget();
        assertEquals(full.rain() * 0.5F, half.rain(), 1e-6F);
        assertEquals(full.snow() * 0.5F, half.snow(), 1e-6F);
        assertEquals(full.reverbMix() * 0.5F, half.reverbMix(), 1e-6F);
        assertEquals(full.reverbSeconds(), half.reverbSeconds());

        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.WATER, 1F, 1F, 1F, 1F));
        assertEquals(0.5F, effects.audioTarget().water());
        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.LAVA, 1F, 1F, 1F, 1F));
        assertEquals(0.5F, effects.audioTarget().lava());
        effects.setEnvironmentStrength(0F);
        assertSame(MusicEffects.AudioTarget.DRY, effects.audioTarget());
    }

    @Test
    @DisplayName("Larger enclosed spaces sustain reflections longer while open spaces have no reverb")
    void roomSizeChangesDurationButDoesNotCreateEnclosure() {
        MusicEffects effects = new MusicEffects();
        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.NONE, 0F, 0F, 1F, 0F));
        MusicEffects.AudioTarget smallRoom = effects.audioTarget();
        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.NONE, 0F, 0F, 1F, 1F));
        MusicEffects.AudioTarget largeRoom = effects.audioTarget();
        assertTrue(largeRoom.reverbSeconds() > smallRoom.reverbSeconds());
        assertTrue(smallRoom.reverbSeconds() >= 0.3F && largeRoom.reverbSeconds() <= 1.2F);
        assertEquals(smallRoom.reverbMix(), largeRoom.reverbMix());
        effects.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.NONE, 0F, 0F, 0F, 1F));
        assertEquals(0F, effects.audioTarget().reverbMix());
    }

    @Test
    @DisplayName("An audio target remains stable after the next game-state snapshot is published")
    void publishedSnapshotsDoNotMutatePreviouslyReadTargets() {
        MusicEffects effects = new MusicEffects();
        MusicEnvironment first = new MusicEnvironment(MusicEnvironment.Liquid.NONE, 0.75F, 0F, 0.5F, 0.5F);
        effects.setEnvironment(first);
        MusicEffects.AudioTarget captured = effects.audioTarget();
        effects.setEnvironment(MusicEnvironment.NEUTRAL);
        assertEquals(MusicEffects.AudioTarget.DRY, effects.audioTarget());
        assertEquals(0.75F, captured.rain());
        assertTrue(captured.reverbMix() > 0F);
        assertEquals(0.75F, first.rain());
        assertThrows(NullPointerException.class, () -> effects.setEnvironment(null));
        assertEquals(MusicEffects.AudioTarget.DRY, effects.audioTarget());
    }

    @Test
    @DisplayName("Invalid environment measurements cannot publish nonfinite or unbounded audio weights")
    void sceneMeasurementsAreNormalizedBeforeAudioConsumption() {
        MusicEnvironment invalid = new MusicEnvironment(MusicEnvironment.Liquid.NONE,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN);
        assertEquals(MusicEnvironment.NEUTRAL, invalid);
        MusicEffects effects = new MusicEffects();
        effects.setEnvironment(invalid);
        assertEquals(MusicEffects.AudioTarget.DRY, effects.audioTarget());

        MusicEnvironment bounded = new MusicEnvironment(MusicEnvironment.Liquid.NONE, -1F, 5F, 2F, -4F);
        assertEquals(new MusicEnvironment(MusicEnvironment.Liquid.NONE, 0F, 1F, 1F, 0F), bounded);
        effects.setEnvironment(bounded);
        assertEquals(1F, effects.audioTarget().snow());
        assertTrue(effects.audioTarget().reverbMix() <= 0.1F);
        effects.setEnvironmentStrength(Float.NaN);
        assertSame(MusicEffects.AudioTarget.DRY, effects.audioTarget());
        assertThrows(NullPointerException.class, () -> new MusicEnvironment(null, 0F, 0F, 0F, 0F));
    }
}
