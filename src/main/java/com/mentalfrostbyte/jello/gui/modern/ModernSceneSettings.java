package com.mentalfrostbyte.jello.gui.modern;

import com.google.gson.JsonObject;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which {@link ModernSceneTheme} SigmaModern paints, persisted as {@code "modernScene"} in the client config.
 *
 * <p>Same shape as {@link com.mentalfrostbyte.jello.gui.ClientModeManager}: forgiving on read (an unknown or
 * malformed saved value leaves the default), and {@code -Dsigma.scene=<theme>} still works as a launch-time
 * override for captures - it wins until the user picks a theme themselves, and is never written to the
 * config.</p>
 */
public final class ModernSceneSettings {
    private static final String CONFIG_KEY = "modernScene";

    private ModernSceneTheme theme = ModernSceneTheme.NIGHT;
    private @Nullable ModernSceneTheme launchOverride =
        ModernSceneTheme.byName(System.getProperty("sigma.scene")).orElse(null);

    public ModernSceneTheme get() {
        return this.launchOverride != null ? this.launchOverride : this.theme;
    }

    public void set(ModernSceneTheme theme) {
        this.theme = Objects.requireNonNull(theme, "theme");
        this.launchOverride = null;
    }

    /** Moves {@code steps} themes along from the one showing (negative goes back) and returns it. */
    public ModernSceneTheme cycle(int steps) {
        set(get().step(steps));
        return this.theme;
    }

    public void read(JsonObject config) {
        if (config.has(CONFIG_KEY) && config.get(CONFIG_KEY).isJsonPrimitive()) {
            ModernSceneTheme.byName(config.get(CONFIG_KEY).getAsString()).ifPresent(saved -> this.theme = saved);
        }
    }

    public void write(JsonObject config) {
        config.addProperty(CONFIG_KEY, this.theme.name());
    }
}
