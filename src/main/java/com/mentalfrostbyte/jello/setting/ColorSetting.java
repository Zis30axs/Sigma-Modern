package com.mentalfrostbyte.jello.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Optional;

/**
 * A colour, packed ARGB.
 *
 * <p>It holds the colour the user chose and nothing else. Anything that changes over time - a rainbow
 * cycle, a fade - is the drawing code's business: a setting that returned a different value on every call
 * would write whatever it happened to be showing into the config.</p>
 */
public final class ColorSetting extends Setting<Integer> {

    private final boolean alphaEnabled;

    public ColorSetting(final String name, final String description, final int defaultValue) {
        this(name, description, defaultValue, false);
    }

    public ColorSetting(final String name, final String description, final int defaultValue, final boolean alphaEnabled) {
        super(name, description, defaultValue);
        this.alphaEnabled = alphaEnabled;
    }

    /** Whether the interface should offer an alpha channel; the value always carries one. */
    public boolean isAlphaEnabled() {
        return this.alphaEnabled;
    }

    public int getAlpha() {
        return this.get() >>> 24 & 0xFF;
    }

    public int getRed() {
        return this.get() >> 16 & 0xFF;
    }

    public int getGreen() {
        return this.get() >> 8 & 0xFF;
    }

    public int getBlue() {
        return this.get() & 0xFF;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(this.get());
    }

    @Override
    protected Optional<Integer> parse(final JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return Optional.of(element.getAsInt());
        }

        return Optional.empty();
    }
}
