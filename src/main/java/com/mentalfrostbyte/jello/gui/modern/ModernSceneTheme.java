package com.mentalfrostbyte.jello.gui.modern;

import java.util.Locale;
import java.util.Optional;

/**
 * The painted backdrops SigmaModern can show behind its menus. Each one is a full repaint of the same
 * {@link ModernScene} machinery - sky, celestial body, ribbons, two mountain ranges, a field with trees and
 * the particles drifting in front - so they all keep the scene's parallax, off-thread painting and crossfade.
 *
 * <p>The constant names are what the config stores, so renaming one drops a saved choice back to
 * {@link #NIGHT}; add new themes freely, but keep existing names stable.</p>
 */
public enum ModernSceneTheme {
    NIGHT("Winter Night"),
    SUNRISE("Sunrise"),
    DUSK("Twilight"),
    ARCTIC_DAY("Arctic Day"),
    SAKURA("Sakura"),
    NEBULA("Nebula"),
    BLOOD_MOON("Blood Moon");

    private final String displayName;

    ModernSceneTheme(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }

    /** The theme {@code steps} places further along (negative goes back), wrapping around. */
    public ModernSceneTheme step(int steps) {
        ModernSceneTheme[] values = values();
        return values[Math.floorMod(this.ordinal() + steps, values.length)];
    }

    /** Case-insensitive, accepting {@code arctic-day} / {@code arctic_day} / {@code ARCTIC_DAY} alike. */
    public static Optional<ModernSceneTheme> byName(String name) {
        if (name == null) return Optional.empty();
        String normalized = name.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        for (ModernSceneTheme theme : values()) {
            if (theme.name().equals(normalized)) return Optional.of(theme);
        }
        return Optional.empty();
    }
}
