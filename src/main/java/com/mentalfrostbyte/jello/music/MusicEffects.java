package com.mentalfrostbyte.jello.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Objects;

/**
 * The music player's local environment audio and visual settings. No game or audio-device dependencies.
 *
 * <p>The game publishes an immutable {@link #setEnvironment environment}; the audio side asks {@link #audioTarget}.
 * Everything is on by default (particles soft) and saved in the Sigma config's {@code "music"} object; an unknown
 * saved value keeps the default, and an out-of-range number is clamped. The spectrum has three dials: its
 * {@linkplain #spectrumHeight() height} (a share of the screen), its {@linkplain #spectrumIntensity() intensity} and
 * its {@linkplain #spectrumOpacity() opacity}.</p>
 */
public final class MusicEffects {
    public enum Particles { OFF, SOFT, STRONG }

    /** Unit filter weights and bounded reverb controls; consumed once per decoded audio block. */
    public record AudioTarget(float water, float lava, float rain, float snow, float reverbMix, float reverbSeconds) {
        public static final AudioTarget DRY = new AudioTarget(0F, 0F, 0F, 0F, 0F, 0.3F);
    }

    private volatile boolean underwaterSound = true, spectrum = true, islandColor = true;
    private volatile boolean lavaSound = true, spaceSound = true, weatherSound = true;
    private volatile float environmentStrength = 1F;
    private volatile Particles particles = Particles.SOFT;
    public static final float MIN_HEIGHT = 0.05F, MAX_HEIGHT = 0.40F, MIN_INTENSITY = 0.5F, MAX_INTENSITY = 2.5F, MIN_OPACITY = 0.2F, MAX_OPACITY = 1F;
    private volatile float spectrumHeight = 0.18F, spectrumIntensity = 1.6F, spectrumOpacity = 0.85F;
    private volatile MusicEnvironment environment = MusicEnvironment.NEUTRAL;

    public boolean underwaterSound() {
        return this.underwaterSound;
    }

    public void setUnderwaterSound(boolean on) {
        this.underwaterSound = on;
    }

    public boolean lavaSound() {
        return this.lavaSound;
    }

    public void setLavaSound(boolean on) {
        this.lavaSound = on;
    }

    public boolean spaceSound() {
        return this.spaceSound;
    }

    public void setSpaceSound(boolean on) {
        this.spaceSound = on;
    }

    public boolean weatherSound() {
        return this.weatherSound;
    }

    public void setWeatherSound(boolean on) {
        this.weatherSound = on;
    }

    public float environmentStrength() {
        return this.environmentStrength;
    }

    public void setEnvironmentStrength(float strength) {
        this.environmentStrength = clamp(strength, 0F, 1F);
    }

    public boolean spectrum() {
        return this.spectrum;
    }

    public void setSpectrum(boolean on) {
        this.spectrum = on;
    }

    /** How high the tallest bar may reach, as a share of the screen's height. */
    public float spectrumHeight() {
        return this.spectrumHeight;
    }

    public void setSpectrumHeight(float share) {
        this.spectrumHeight = clamp(share, MIN_HEIGHT, MAX_HEIGHT);
    }

    /** How hard the bars react: above 1 they reach higher and quiet and loud stand further apart. */
    public float spectrumIntensity() {
        return this.spectrumIntensity;
    }

    public void setSpectrumIntensity(float intensity) {
        this.spectrumIntensity = clamp(intensity, MIN_INTENSITY, MAX_INTENSITY);
    }

    public float spectrumOpacity() {
        return this.spectrumOpacity;
    }

    public void setSpectrumOpacity(float opacity) {
        this.spectrumOpacity = clamp(opacity, MIN_OPACITY, MAX_OPACITY);
    }

    private static float clamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    public boolean islandColor() {
        return this.islandColor;
    }

    public void setIslandColor(boolean on) {
        this.islandColor = on;
    }

    public Particles particles() {
        return this.particles;
    }

    public void setParticles(Particles particles) {
        this.particles = Objects.requireNonNull(particles, "particles");
    }

    public void setEnvironment(MusicEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** An enabled liquid effect takes priority over weather and room acoustics. */
    public AudioTarget audioTarget() {
        MusicEnvironment scene = this.environment;
        float strength = this.environmentStrength;
        if (strength == 0F) return AudioTarget.DRY;
        if (scene.liquid() == MusicEnvironment.Liquid.WATER && this.underwaterSound) {
            return new AudioTarget(strength, 0F, 0F, 0F, 0F, 0.3F);
        }
        if (scene.liquid() == MusicEnvironment.Liquid.LAVA && this.lavaSound) {
            return new AudioTarget(0F, strength, 0F, 0F, 0F, 0.3F);
        }
        return new AudioTarget(0F, 0F,
            this.weatherSound ? scene.rain() * strength : 0F,
            this.weatherSound ? scene.snow() * strength : 0F,
            this.spaceSound ? scene.enclosure() * strength * 0.1F : 0F,
            0.3F + scene.spaceSize() * 0.9F);
    }

    public void read(JsonObject config) {
        JsonElement element = config.get("music");
        if (element == null || !element.isJsonObject()) return;
        JsonObject music = element.getAsJsonObject();
        Boolean on = flag(music, "fxUnderwater");
        if (on != null) this.underwaterSound = on;
        // Older versions used the underwater switch for both liquids. Preserve an explicit opt-out.
        Boolean lava = flag(music, "fxLava");
        if (lava != null) this.lavaSound = lava;
        else if (!music.has("fxLava") && on != null) this.lavaSound = on;
        on = flag(music, "fxSpace");
        if (on != null) this.spaceSound = on;
        on = flag(music, "fxWeather");
        if (on != null) this.weatherSound = on;
        on = flag(music, "fxSpectrum");
        if (on != null) this.spectrum = on;
        on = flag(music, "fxIslandColor");
        if (on != null) this.islandColor = on;
        Float number = number(music, "fxEnvironmentStrength");
        if (number != null) setEnvironmentStrength(number);
        number = number(music, "fxSpectrumHeight");
        if (number != null) setSpectrumHeight(number);
        number = number(music, "fxSpectrumIntensity");
        if (number != null) setSpectrumIntensity(number);
        number = number(music, "fxSpectrumOpacity");
        if (number != null) setSpectrumOpacity(number);
        JsonElement particles = music.get("fxParticles");
        if (particles != null && particles.isJsonPrimitive()) {
            try {
                this.particles = Particles.valueOf(particles.getAsString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Keep the default.
            }
        }
    }

    /** Adds the effects settings to the {@code "music"} object, keeping whatever else is saved there. */
    public void write(JsonObject config) {
        JsonElement existing = config.get("music");
        JsonObject music = existing != null && existing.isJsonObject() ? existing.getAsJsonObject() : new JsonObject();
        music.addProperty("fxUnderwater", this.underwaterSound);
        music.addProperty("fxLava", this.lavaSound);
        music.addProperty("fxSpace", this.spaceSound);
        music.addProperty("fxWeather", this.weatherSound);
        music.addProperty("fxEnvironmentStrength", this.environmentStrength);
        music.addProperty("fxSpectrum", this.spectrum);
        music.addProperty("fxIslandColor", this.islandColor);
        music.addProperty("fxParticles", this.particles.name().toLowerCase(Locale.ROOT));
        music.addProperty("fxSpectrumHeight", this.spectrumHeight);
        music.addProperty("fxSpectrumIntensity", this.spectrumIntensity);
        music.addProperty("fxSpectrumOpacity", this.spectrumOpacity);
        config.add("music", music);
    }

    private static Float number(JsonObject music, String name) {
        JsonElement value = music.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        float number = value.getAsFloat();
        return Float.isFinite(number) ? number : null;
    }

    private static Boolean flag(JsonObject music, String name) {
        JsonElement value = music.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
    }
}
