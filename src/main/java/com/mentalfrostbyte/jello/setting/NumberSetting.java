package com.mentalfrostbyte.jello.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Optional;
import net.minecraft.util.Mth;

/**
 * A number the user picks from a range.
 *
 * <p>Values are held as {@code float} and clamped to {@code [min, max]} whatever they come from, so a
 * hand-edited config cannot put a setting outside the range its owner was written for. {@link #getStep()}
 * is what the interface moves by; it does not constrain the stored value.</p>
 */
public final class NumberSetting extends Setting<Float> {

    private final float min;

    private final float max;

    private final float step;

    public NumberSetting(final String name, final String description,
                         final float defaultValue, final float min, final float max, final float step) {
        super(name, description, Mth.clamp(defaultValue, min, max));
        if (min > max) {
            throw new IllegalArgumentException("Setting '" + name + "': min " + min + " is above max " + max);
        }

        if (step <= 0.0F) {
            throw new IllegalArgumentException("Setting '" + name + "': step must be positive, got " + step);
        }

        this.min = min;
        this.max = max;
        this.step = step;
    }

    public float getMin() {
        return this.min;
    }

    public float getMax() {
        return this.max;
    }

    public float getStep() {
        return this.step;
    }

    public int getInt() {
        return Math.round(this.get());
    }

    /** How many decimals the interface should show, taken from the step. */
    public int getDecimalPlaces() {
        if (this.step >= 1.0F) {
            return 0;
        }

        String text = Float.toString(this.step);
        int point = text.indexOf('.');
        return point < 0 ? 0 : text.length() - point - 1;
    }

    @Override
    protected Float sanitise(final Float value) {
        return Mth.clamp(value, this.min, this.max);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(this.get());
    }

    @Override
    protected Optional<Float> parse(final JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            float value = element.getAsFloat();
            return Float.isFinite(value) ? Optional.of(value) : Optional.empty();
        }

        return Optional.empty();
    }
}
