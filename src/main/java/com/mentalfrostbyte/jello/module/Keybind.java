package com.mentalfrostbyte.jello.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Objects;
import java.util.Optional;

/**
 * The key a module answers to, and what pressing it means.
 *
 * <p>The key is an {@link InputConstants.Key}, so a keyboard key and a mouse button are told apart by
 * construction rather than by two integer spaces that happen to overlap. It is persisted by the key's
 * name - {@code key.keyboard.r}, {@code key.mouse.middle} - which is stable across versions and readable
 * in the config.</p>
 *
 * @param key  the key, or {@link InputConstants#UNKNOWN} when nothing is bound
 * @param mode what a press does
 */
public record Keybind(InputConstants.Key key, BindMode mode) {

    public static final Keybind UNBOUND = new Keybind(InputConstants.UNKNOWN, BindMode.TOGGLE);

    public Keybind {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
    }

    public static Keybind of(final InputConstants.Key key) {
        return new Keybind(key, BindMode.TOGGLE);
    }

    public boolean isBound() {
        return !InputConstants.UNKNOWN.equals(this.key);
    }

    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("key", this.key.getName());
        json.addProperty("mode", this.mode.name());
        return json;
    }

    /**
     * Reads a keybind the config had. Accepts the object form this class writes, and a bare key name as a
     * convenience for hand-edited configs. Anything else - including a key name this game does not know -
     * reads back as empty, and the caller keeps whatever the module already had.
     */
    public static Optional<Keybind> fromJson(final JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return parseKey(element.getAsString()).map(Keybind::of);
        }

        if (!element.isJsonObject()) {
            return Optional.empty();
        }

        JsonObject json = element.getAsJsonObject();
        if (!json.has("key")) {
            return Optional.empty();
        }

        Optional<InputConstants.Key> key = parseKey(json.get("key").getAsString());
        if (key.isEmpty()) {
            return Optional.empty();
        }

        BindMode mode = BindMode.TOGGLE;
        if (json.has("mode")) {
            String name = json.get("mode").getAsString();
            for (BindMode candidate : BindMode.values()) {
                if (candidate.name().equals(name)) {
                    mode = candidate;
                    break;
                }
            }
        }

        return Optional.of(new Keybind(key.get(), mode));
    }

    private static Optional<InputConstants.Key> parseKey(final String name) {
        try {
            return Optional.of(InputConstants.getKey(name));
        } catch (IllegalArgumentException unknownName) {
            return Optional.empty();
        }
    }
}
