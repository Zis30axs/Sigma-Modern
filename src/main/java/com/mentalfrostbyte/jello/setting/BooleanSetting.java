package com.mentalfrostbyte.jello.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Optional;

/** An on/off switch. */
public final class BooleanSetting extends Setting<Boolean> {

    public BooleanSetting(final String name, final String description, final boolean defaultValue) {
        super(name, description, defaultValue);
    }

    public void toggle() {
        this.set(!this.get());
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(this.get());
    }

    @Override
    protected Optional<Boolean> parse(final JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return Optional.of(element.getAsBoolean());
        }

        return Optional.empty();
    }
}
