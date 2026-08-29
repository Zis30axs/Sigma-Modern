package com.mentalfrostbyte.jello.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Optional;

/** Free-form text the user types. */
public final class TextSetting extends Setting<String> {

    private final int maxLength;

    public TextSetting(final String name, final String description, final String defaultValue) {
        this(name, description, defaultValue, 256);
    }

    public TextSetting(final String name, final String description, final String defaultValue, final int maxLength) {
        super(name, description, defaultValue);
        this.maxLength = maxLength;
    }

    public int getMaxLength() {
        return this.maxLength;
    }

    public boolean isBlank() {
        return this.get().isBlank();
    }

    @Override
    protected String sanitise(final String value) {
        return value.length() > this.maxLength ? value.substring(0, this.maxLength) : value;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(this.get());
    }

    @Override
    protected Optional<String> parse(final JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return Optional.of(element.getAsString());
        }

        return Optional.empty();
    }
}
