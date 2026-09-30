package com.mentalfrostbyte.jello.gui.legacy;

import com.mentalfrostbyte.jello.module.Keybind;

/** How the old ClickGUIs name a module's key. */
public final class LegacyKeys {
    private LegacyKeys() {
    }

    /** "None" for an unbound module, otherwise the key as a person reads it: {@code R}, {@code Right Shift}, {@code Middle Button}. */
    public static String name(final Keybind keybind) {
        return keybind.isBound() ? keybind.key().getDisplayName().getString() : "None";
    }
}
