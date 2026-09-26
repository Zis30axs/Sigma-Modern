package com.mentalfrostbyte.jello.music;

import java.util.Objects;

/** Immutable, game-independent measurements published by the game thread to the audio thread. */
public record MusicEnvironment(Liquid liquid, float rain, float snow, float enclosure, float spaceSize) {
    public enum Liquid { NONE, WATER, LAVA }

    public static final MusicEnvironment NEUTRAL = new MusicEnvironment(Liquid.NONE, 0F, 0F, 0F, 0F);

    public MusicEnvironment {
        Objects.requireNonNull(liquid, "liquid");
        rain = unit(rain);
        snow = unit(snow);
        enclosure = unit(enclosure);
        spaceSize = unit(spaceSize);
    }

    private static float unit(float value) {
        return Float.isFinite(value) ? Math.clamp(value, 0F, 1F) : 0F;
    }
}
