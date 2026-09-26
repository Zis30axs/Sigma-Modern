package com.mentalfrostbyte.jello.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mentalfrostbyte.jello.music.lyrics.LyricsService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The player, library and effects share the config's "music" object and must preserve each other's settings. */
class MusicSettingsConfigTest {
    private static MusicPlayer player() {
        MusicSource empty = new MusicSource() {
            @Override public String name() { return "Test"; }
            @Override public List<Track> tracks() { return List.of(); }
        };
        return new MusicPlayer(new SilentBackend(System::nanoTime), empty);
    }

    @Test
    @DisplayName("Effects, player volume and lyric preferences survive writes in either order")
    void volumeAndLyricSettingsSurviveEachOthersWrites() {
        MusicLibrary library = new MusicLibrary(null);
        try {
            library.lyrics().setChannel(LyricsService.Channel.QQ);
            library.lyrics().setMode(LyricsService.Mode.WORD);
            library.lyrics().setLanguage(LyricsService.Language.ROMANIZATION);
            MusicPlayer player = player();
            player.setVolume(0.6F);
            MusicEffects effects = new MusicEffects();
            effects.setWeatherSound(false);
            effects.setEnvironmentStrength(0.4F);

            JsonObject config = new JsonObject();
            library.write(config);
            player.write(config);  // written after the library: must merge, not replace
            effects.write(config);
            library.write(config);
            player.write(config);
            JsonObject music = config.getAsJsonObject("music");
            assertEquals("qq", music.get("lyricChannel").getAsString());
            assertEquals("word", music.get("lyricMode").getAsString());
            assertEquals("romanization", music.get("lyricLanguage").getAsString());
            assertEquals(0.6F, music.get("volume").getAsFloat(), 1e-6F);
            assertFalse(music.get("fxWeather").getAsBoolean());
            assertEquals(0.4F, music.get("fxEnvironmentStrength").getAsFloat(), 1e-6F);

            MusicLibrary reread = new MusicLibrary(null);
            try {
                reread.read(config);
                assertEquals(LyricsService.Channel.QQ, reread.lyrics().channel());
                assertEquals(LyricsService.Mode.WORD, reread.lyrics().mode());
                assertEquals(LyricsService.Language.ROMANIZATION, reread.lyrics().language());
            } finally {
                reread.close();
            }
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("Environment preferences round-trip without persisting the current scene")
    void effectsSettingsRoundTripAndIgnoreJunk() {
        MusicEffects fx = new MusicEffects();
        fx.setUnderwaterSound(false);
        fx.setLavaSound(true);
        fx.setSpaceSound(false);
        fx.setWeatherSound(false);
        fx.setEnvironmentStrength(0.35F);
        fx.setParticles(MusicEffects.Particles.STRONG);
        fx.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.LAVA, 0F, 0F, 1F, 1F));
        JsonObject config = new JsonObject();
        fx.write(config);
        player().write(config);
        MusicEffects reread = new MusicEffects();
        reread.read(config);
        assertFalse(reread.underwaterSound());
        assertTrue(reread.lavaSound());
        assertFalse(reread.spaceSound());
        assertFalse(reread.weatherSound());
        assertEquals(0.35F, reread.environmentStrength(), 1e-6F);
        assertTrue(reread.spectrum());
        assertEquals(MusicEffects.Particles.STRONG, reread.particles());
        assertEquals(MusicEffects.AudioTarget.DRY, reread.audioTarget());

        MusicEffects defaults = new MusicEffects();
        defaults.read(JsonParser.parseString("{\"music\":{\"fxParticles\":\"confetti\",\"fxSpectrum\":\"yes\"}}").getAsJsonObject());
        assertEquals(MusicEffects.Particles.SOFT, defaults.particles());
        assertTrue(defaults.spectrum());
        defaults.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.WATER, 0F, 0F, 0F, 0F));
        assertEquals(1F, defaults.audioTarget().water());
        defaults.setUnderwaterSound(false);
        assertEquals(MusicEffects.AudioTarget.DRY, defaults.audioTarget(), "the setting off never muffles");
    }

    @Test
    @DisplayName("Legacy underwater opt-out also disables lava unless a new lava preference is present")
    void legacyLiquidPreferenceMigratesOnlyWhenLavaIsMissing() {
        MusicEffects legacy = new MusicEffects();
        legacy.read(JsonParser.parseString("{\"music\":{\"fxUnderwater\":false}}").getAsJsonObject());
        legacy.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.LAVA, 0F, 0F, 0F, 0F));
        assertEquals(MusicEffects.AudioTarget.DRY, legacy.audioTarget());
        assertTrue(legacy.spaceSound(), "new environment features retain their defaults");
        assertTrue(legacy.weatherSound());

        MusicEffects explicit = new MusicEffects();
        explicit.read(JsonParser.parseString("{\"music\":{\"fxUnderwater\":false,\"fxLava\":true}}").getAsJsonObject());
        explicit.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.LAVA, 0F, 0F, 0F, 0F));
        assertEquals(1F, explicit.audioTarget().lava());

        MusicEffects explicitOff = new MusicEffects();
        explicitOff.read(JsonParser.parseString("{\"music\":{\"fxUnderwater\":true,\"fxLava\":false}}").getAsJsonObject());
        explicitOff.setEnvironment(new MusicEnvironment(MusicEnvironment.Liquid.LAVA, 0F, 0F, 0F, 0F));
        assertEquals(MusicEffects.AudioTarget.DRY, explicitOff.audioTarget());
    }

    @Test
    @DisplayName("Malformed environment values retain defaults and do not act like legacy missing keys")
    void malformedEnvironmentValuesKeepDefaults() {
        MusicEffects effects = new MusicEffects();
        effects.read(JsonParser.parseString("""
            {"music":{"fxUnderwater":false,"fxLava":"off","fxSpace":[],
                "fxWeather":null,"fxEnvironmentStrength":"half"}}
            """).getAsJsonObject());
        assertFalse(effects.underwaterSound());
        assertTrue(effects.lavaSound(), "present but malformed lava does not inherit the old opt-out");
        assertTrue(effects.spaceSound());
        assertTrue(effects.weatherSound());
        assertEquals(1F, effects.environmentStrength());
        effects.read(JsonParser.parseString("{\"music\":false}").getAsJsonObject());
        assertEquals(1F, effects.environmentStrength());
    }

    @Test
    @DisplayName("Finite saved strength is clamped while nonfinite saved numbers are ignored")
    void persistedEnvironmentStrengthClampsFiniteValuesAndIgnoresNonfiniteValues() {
        MusicEffects effects = new MusicEffects();
        effects.read(JsonParser.parseString("{\"music\":{\"fxEnvironmentStrength\":8}}").getAsJsonObject());
        assertEquals(1F, effects.environmentStrength());
        effects.read(JsonParser.parseString("{\"music\":{\"fxEnvironmentStrength\":-2}}").getAsJsonObject());
        assertEquals(0F, effects.environmentStrength());

        effects.setEnvironmentStrength(0.45F);
        JsonObject config = new JsonObject();
        JsonObject music = new JsonObject();
        config.add("music", music);
        for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            music.addProperty("fxEnvironmentStrength", value);
            effects.read(config);
            assertEquals(0.45F, effects.environmentStrength(), 1e-6F);
        }
        effects.read(JsonParser.parseString("{\"music\":{\"fxEnvironmentStrength\":1e1000}}").getAsJsonObject());
        assertEquals(0.45F, effects.environmentStrength(), 1e-6F);
    }

    @Test
    void spectrumDialsRoundTripAndClampJunk() {
        MusicEffects fx = new MusicEffects();
        fx.setSpectrumHeight(0.3F);
        fx.setSpectrumIntensity(2.2F);
        fx.setSpectrumOpacity(0.5F);
        JsonObject config = new JsonObject();
        fx.write(config);
        MusicEffects reread = new MusicEffects();
        reread.read(config);
        assertEquals(0.3F, reread.spectrumHeight(), 1e-6F);
        assertEquals(2.2F, reread.spectrumIntensity(), 1e-6F);
        assertEquals(0.5F, reread.spectrumOpacity(), 1e-6F);

        MusicEffects clamped = new MusicEffects();
        clamped.read(JsonParser.parseString("{\"music\":{\"fxSpectrumHeight\":5,\"fxSpectrumIntensity\":-1,\"fxSpectrumOpacity\":\"max\"}}").getAsJsonObject());
        assertEquals(MusicEffects.MAX_HEIGHT, clamped.spectrumHeight(), 1e-6F);
        assertEquals(MusicEffects.MIN_INTENSITY, clamped.spectrumIntensity(), 1e-6F);
        assertEquals(0.85F, clamped.spectrumOpacity(), 1e-6F, "a non-number keeps the default");
        clamped.setSpectrumOpacity(Float.NaN);
        assertEquals(MusicEffects.MIN_OPACITY, clamped.spectrumOpacity(), 1e-6F);
    }

    @Test
    void unknownSavedValuesKeepTheDefaults() {
        MusicLibrary library = new MusicLibrary(null);
        try {
            library.read(JsonParser.parseString("{\"music\":{\"lyricChannel\":\"spotify\",\"lyricMode\":7}}").getAsJsonObject());
            assertEquals(LyricsService.Channel.MIX, library.lyrics().channel());
            assertEquals(LyricsService.Mode.AUTO, library.lyrics().mode());
            assertEquals(LyricsService.Language.TRANSLATION, library.lyrics().language());
            library.read(JsonParser.parseString("{\"music\":\"broken\"}").getAsJsonObject());
            assertEquals(LyricsService.Mode.AUTO, library.lyrics().mode());
        } finally {
            library.close();
        }
    }
}
