package com.mentalfrostbyte.jello.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * The music player's effects settings and the game state they react to: the under-water sound (the music muffled
 * while the player's head is in water or lava - done to the samples here, heard by no one else), the spectrum
 * along the bottom of the screen, beat particles, and tinting the island with the cover's colour.
 *
 * <p>No game code: the game side tells it {@link #setSubmerged}, the audio side asks {@link #muffleTarget}.
 * Everything is on by default (particles soft) and saved in the Sigma config's {@code "music"} object; an unknown
 * saved value keeps the default, and an out-of-range number is clamped. The spectrum has three dials: its
 * {@linkplain #spectrumHeight() height} (a share of the screen), its {@linkplain #spectrumIntensity() intensity} and
 * its {@linkplain #spectrumOpacity() opacity}.</p>
 */
public final class MusicEffects {
    public enum Particles { OFF, SOFT, STRONG }

    private volatile boolean underwaterSound = true, spectrum = true, islandColor = true;
    private volatile Particles particles = Particles.SOFT;
    public static final float MIN_HEIGHT = 0.05F, MAX_HEIGHT = 0.40F, MIN_INTENSITY = 0.5F, MAX_INTENSITY = 2.5F, MIN_OPACITY = 0.2F, MAX_OPACITY = 1F;
    private volatile float spectrumHeight = 0.18F, spectrumIntensity = 1.6F, spectrumOpacity = 0.85F;
    private volatile boolean submerged;

    public boolean underwaterSound() {
        return this.underwaterSound;
    }

    public void setUnderwaterSound(boolean on) {
        this.underwaterSound = on;
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
        this.particles = particles;
    }

    /** The game's side: whether the player's head is in a liquid now (false with no player). */
    public void setSubmerged(boolean submerged) {
        this.submerged = submerged;
    }

    /** What the audio should head for: 1 (muffled) with the head under and the setting on, else 0. */
    public float muffleTarget() {
        return this.underwaterSound && this.submerged ? 1F : 0F;
    }

    public void read(JsonObject config) {
        JsonElement element = config.get("music");
        if (element == null || !element.isJsonObject()) return;
        JsonObject music = element.getAsJsonObject();
        Boolean on = flag(music, "fxUnderwater");
        if (on != null) this.underwaterSound = on;
        on = flag(music, "fxSpectrum");
        if (on != null) this.spectrum = on;
        on = flag(music, "fxIslandColor");
        if (on != null) this.islandColor = on;
        Float number = number(music, "fxSpectrumHeight");
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
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsFloat() : null;
    }

    private static Boolean flag(JsonObject music, String name) {
        JsonElement value = music.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
    }
}
