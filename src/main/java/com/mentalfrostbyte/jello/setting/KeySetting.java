package com.mentalfrostbyte.jello.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/** A keyboard shortcut owned by a feature, independently of the module's enable/disable binding. */
public final class KeySetting extends Setting<KeySetting.Chord> {
    public static final int MODIFIER_MASK = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT
            | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER;

    public record Chord(InputConstants.Key key, int modifiers) {
        public static final Chord UNBOUND = new Chord(InputConstants.UNKNOWN, 0);

        public Chord {
            Objects.requireNonNull(key, "key");
            if ((modifiers & ~MODIFIER_MASK) != 0) throw new IllegalArgumentException("Unsupported shortcut modifiers");
            if (InputConstants.UNKNOWN.equals(key)) {
                if (modifiers != 0) throw new IllegalArgumentException("An unbound shortcut cannot have modifiers");
            } else if (key.getType() == InputConstants.Type.MOUSE
                    || key.getType() == InputConstants.Type.KEYSYM && (key.getValue() < GLFW.GLFW_KEY_SPACE
                    || key.getValue() > GLFW.GLFW_KEY_LAST || isModifier(key.getValue()))
                    || key.getType() == InputConstants.Type.SCANCODE && (key.getValue() <= 0 || key.getValue() > 65535)) {
                throw new IllegalArgumentException("A shortcut needs a keyboard key other than a modifier");
            }
        }

        public static Chord of(final InputConstants.Key key, final int modifiers) { return new Chord(key, modifiers); }
        public boolean isBound() { return !InputConstants.UNKNOWN.equals(this.key); }
    }

    public KeySetting(final String name, final String description, final Chord defaultValue) {
        super(name, description, defaultValue);
    }

    /** Locks do not change a chord; extra Ctrl/Shift/Alt/Super modifiers do. */
    public boolean matches(final KeyEvent event) {
        return this.get().isBound() && this.get().key().equals(InputConstants.getKey(event))
                && this.get().modifiers() == (event.modifiers() & MODIFIER_MASK);
    }

    /** Modifier presses keep capture open. Escape and clear keys are handled by the capture UI. */
    public static Optional<Chord> capture(final KeyEvent event) {
        if (isModifier(event.key()) || event.key() == GLFW.GLFW_KEY_UNKNOWN && event.scancode() <= 0) return Optional.empty();
        try {
            return Optional.of(new Chord(InputConstants.getKey(event), event.modifiers() & MODIFIER_MASK));
        } catch (IllegalArgumentException invalidKey) {
            return Optional.empty();
        }
    }

    private static boolean isModifier(final int key) {
        return key >= GLFW.GLFW_KEY_LEFT_SHIFT && key <= GLFW.GLFW_KEY_RIGHT_SUPER;
    }

    /** A readable stable label that does not require a native window to have been created. */
    public String displayLabel() {
        Chord chord = this.get();
        if (!chord.isBound()) return "None";
        StringBuilder label = new StringBuilder();
        if ((chord.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) label.append("Ctrl + ");
        if ((chord.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) label.append("Shift + ");
        if ((chord.modifiers() & GLFW.GLFW_MOD_ALT) != 0) label.append("Alt + ");
        if ((chord.modifiers() & GLFW.GLFW_MOD_SUPER) != 0) label.append("Super + ");
        String keyName = chord.key().getName();
        if (keyName.startsWith("key.keyboard.")) keyName = keyName.substring("key.keyboard.".length());
        for (String part : keyName.split("[._]")) {
            if (part.isEmpty()) continue;
            if (label.length() > 0 && label.charAt(label.length() - 1) != ' ') label.append(' ');
            label.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return label.toString();
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("key", this.get().key().getName());
        json.addProperty("modifiers", this.get().modifiers());
        return json;
    }

    @Override
    protected Optional<Chord> parse(final JsonElement element) {
        if (element == null || !element.isJsonObject()) return Optional.empty();
        JsonObject json = element.getAsJsonObject();
        JsonElement key = json.get("key");
        JsonElement modifiers = json.get("modifiers");
        if (key == null || !key.isJsonPrimitive() || !key.getAsJsonPrimitive().isString()
                || modifiers == null || !modifiers.isJsonPrimitive() || !modifiers.getAsJsonPrimitive().isNumber()) return Optional.empty();
        String name = key.getAsString();
        if (!name.startsWith("key.keyboard.") && !name.startsWith("scancode.")) return Optional.empty();
        try {
            int mask = modifiers.getAsBigDecimal().intValueExact();
            return Optional.of(new Chord(InputConstants.getKey(name), mask));
        } catch (IllegalArgumentException | ArithmeticException invalidValue) {
            return Optional.empty();
        }
    }
}
