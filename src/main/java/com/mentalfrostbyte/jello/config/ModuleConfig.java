package com.mentalfrostbyte.jello.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.module.Keybind;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.Setting;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes the {@code modules} section of a config.
 *
 * <p>Modules are keyed by name and settings by name inside them, so nothing depends on registration order
 * or on how many settings a module happened to have when the file was written:</p>
 *
 * <pre>{@code
 * "modules": {
 *   "CustomTitle": {
 *     "enabled": true,
 *     "keybind": { "key": "key.keyboard.r", "mode": "TOGGLE" },
 *     "settings": { "Preset": "SIGMA" }
 *   }
 * }
 * }</pre>
 *
 * <p>Reading is forgiving about the file and strict about nothing else. A module or a setting the config
 * mentions but the client no longer has is noted and skipped - that is what an old config looks like after
 * a rename. A value that is there but unusable leaves the setting at its default and is logged as a
 * warning, because it is a value the user will notice going missing.</p>
 *
 * <p>This works entirely through a config object handed to it, and holds no state, so a layer above can
 * one day keep several of these objects around as profiles without anything here changing.</p>
 */
public final class ModuleConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Config");

    private static final String MODULES = "modules";

    private static final String ENABLED = "enabled";

    private static final String KEYBIND = "keybind";

    private static final String SETTINGS = "settings";

    private ModuleConfig() {
    }

    /** Applies everything {@code root} has to say about modules. Settings are applied before the on/off
     * state, so a module that is switched on during startup already sees its configured values. */
    public static void read(final JsonObject root, final ModuleManager modules) {
        if (!root.has(MODULES) || !root.get(MODULES).isJsonObject()) {
            LOGGER.debug("No module config yet, every module stays at its defaults");
            return;
        }

        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject(MODULES).entrySet()) {
            Optional<Module> module = modules.find(entry.getKey());
            if (module.isEmpty()) {
                LOGGER.debug("Config mentions module '{}', which this client does not have - skipping it", entry.getKey());
                continue;
            }

            if (!entry.getValue().isJsonObject()) {
                LOGGER.warn("Config entry for module '{}' is not an object, ignoring it", entry.getKey());
                continue;
            }

            readModule(module.get(), entry.getValue().getAsJsonObject());
        }
    }

    private static void readModule(final Module module, final JsonObject json) {
        if (json.has(SETTINGS)) {
            if (json.get(SETTINGS).isJsonObject()) {
                readSettings(module, json.getAsJsonObject(SETTINGS));
            } else {
                LOGGER.warn("{}: 'settings' is not an object, keeping the defaults", module.getName());
            }
        }

        if (json.has(KEYBIND)) {
            Optional<Keybind> keybind = Keybind.fromJson(json.get(KEYBIND));
            if (keybind.isPresent()) {
                module.setKeybind(keybind.get());
            } else {
                LOGGER.warn("{}: could not read the keybind {}, leaving it unbound", module.getName(), json.get(KEYBIND));
            }
        }

        if (json.has(ENABLED)) {
            JsonElement enabled = json.get(ENABLED);
            if (enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean()) {
                module.setEnabled(enabled.getAsBoolean());
            } else {
                LOGGER.warn("{}: 'enabled' is not a boolean, leaving the module off", module.getName());
            }
        }
    }

    private static void readSettings(final Module module, final JsonObject json) {
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            Optional<Setting<?>> setting = module.setting(entry.getKey());
            if (setting.isEmpty()) {
                LOGGER.debug("{}: config has a setting '{}' that no longer exists - skipping it",
                        module.getName(), entry.getKey());
                continue;
            }

            if (!setting.get().fromJson(entry.getValue())) {
                LOGGER.warn("{}.{}: cannot use the saved value {}, falling back to the default {}",
                        module.getName(), entry.getKey(), entry.getValue(), setting.get().getDefaultValue());
            }
        }
    }

    /** Replaces the {@code modules} section of {@code root} with the modules' current state. */
    public static void write(final JsonObject root, final ModuleManager modules) {
        JsonObject all = new JsonObject();
        for (Module module : modules.all()) {
            JsonObject json = new JsonObject();
            json.addProperty(ENABLED, module.isEnabled());
            if (module.getKeybind().isBound()) {
                json.add(KEYBIND, module.getKeybind().toJson());
            }

            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.settings()) {
                settings.add(setting.getName(), setting.toJson());
            }

            if (!settings.isEmpty()) {
                json.add(SETTINGS, settings);
            }

            all.add(module.getName(), json);
        }

        root.add(MODULES, all);
    }
}
