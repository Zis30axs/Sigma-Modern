package com.mentalfrostbyte.jello.music;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mentalfrostbyte.jello.music.lyrics.LyricsService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The player and the library share the config's "music" object: neither may wipe the other's settings. */
class MusicSettingsConfigTest {
    private static MusicPlayer player() {
        MusicSource empty = new MusicSource() {
            @Override public String name() { return "Test"; }
            @Override public List<Track> tracks() { return List.of(); }
        };
        return new MusicPlayer(new SilentBackend(System::nanoTime), empty);
    }

    @Test
    void volumeAndLyricSettingsSurviveEachOthersWrites() {
        MusicLibrary library = new MusicLibrary(null);
        try {
            library.lyrics().setChannel(LyricsService.Channel.QQ);
            library.lyrics().setMode(LyricsService.Mode.WORD);
            library.lyrics().setLanguage(LyricsService.Language.ROMANIZATION);
            MusicPlayer player = player();
            player.setVolume(0.6F);

            JsonObject config = new JsonObject();
            library.write(config);
            player.write(config);  // written after the library: must merge, not replace
            JsonObject music = config.getAsJsonObject("music");
            assertEquals("qq", music.get("lyricChannel").getAsString());
            assertEquals("word", music.get("lyricMode").getAsString());
            assertEquals("romanization", music.get("lyricLanguage").getAsString());
            assertEquals(0.6F, music.get("volume").getAsFloat(), 1e-6F);

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
    void effectsSettingsRoundTripAndIgnoreJunk() {
        MusicEffects fx = new MusicEffects();
        fx.setUnderwaterSound(false);
        fx.setParticles(MusicEffects.Particles.STRONG);
        JsonObject config = new JsonObject();
        fx.write(config);
        player().write(config);
        MusicEffects reread = new MusicEffects();
        reread.read(config);
        assertEquals(false, reread.underwaterSound());
        assertEquals(true, reread.spectrum());
        assertEquals(MusicEffects.Particles.STRONG, reread.particles());

        MusicEffects defaults = new MusicEffects();
        defaults.read(JsonParser.parseString("{\"music\":{\"fxParticles\":\"confetti\",\"fxSpectrum\":\"yes\"}}").getAsJsonObject());
        assertEquals(MusicEffects.Particles.SOFT, defaults.particles());
        assertEquals(true, defaults.spectrum());
        defaults.setSubmerged(true);
        assertEquals(1F, defaults.muffleTarget());
        defaults.setUnderwaterSound(false);
        assertEquals(0F, defaults.muffleTarget(), "the setting off never muffles");
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
