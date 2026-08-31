package com.mentalfrostbyte.jello.gui;

import com.google.gson.JsonObject;
import java.util.Objects;

/**
 * Single source of truth for the active {@link ClientMode}.
 *
 * <p>It only owns the selected presentation mode. It does not touch the module registry, module enabled
 * state, or any module metadata.</p>
 */
public final class ClientModeManager {

    private static final String CONFIG_KEY = "clientMode";

    private ClientMode mode = ClientMode.JELLO;

    public ClientMode get() {
        return this.mode;
    }

    public void set(final ClientMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /** Advances to the next presentation mode and returns it. */
    public ClientMode cycle() {
        ClientMode[] values = ClientMode.values();
        this.mode = values[(this.mode.ordinal() + 1) % values.length];
        return this.mode;
    }

    public void read(final JsonObject config) {
        if (config.has(CONFIG_KEY) && config.get(CONFIG_KEY).isJsonPrimitive()) {
            String name = config.get(CONFIG_KEY).getAsString();
            for (ClientMode candidate : ClientMode.values()) {
                if (candidate.name().equals(name)) {
                    this.mode = candidate;
                    return;
                }
            }
            if ("NOADDONS".equals(name) || "INDETERMINATE".equals(name)) {
                this.mode = ClientMode.NO_ADDONS;
            }
        }
    }

    public void write(final JsonObject config) {
        config.addProperty(CONFIG_KEY, this.mode.name());
    }
}
