package com.mentalfrostbyte.jello.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.module.Keybind;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.Setting;
import java.util.HashMap;
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
 * warning, because it is a value the user will notice going missing. A module the config has no usable
 * on/off state for - one added since the file was written, say - is switched to its
 * {@linkplain Module#isEnabledByDefault() default}.</p>
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

    /** Applies everything {@code root} has to say about modules, and puts every module it says nothing about
     * in its default state. Settings are applied before the on/off state, so a module that is switched on during
     * startup already sees its configured values. */
    public static void read(final JsonObject root, final ModuleManager modules) {
        Map<Module, JsonObject> saved = new HashMap<>();
        if (root.has(MODULES) && root.get(MODULES).isJsonObject()) {
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

                saved.put(module.get(), entry.getValue().getAsJsonObject());
            }
        } else {
            LOGGER.debug("No module config yet, every module starts in its default state");
        }

        for (Module module : modules.all()) {
            JsonObject json = saved.get(module);
            if (json == null || !readModule(module, json)) {
                module.setEnabled(module.isEnabledByDefault());
            }
        }
    }

    /** Returns whether the config had a usable on/off state for the module, which has then been applied. */
    private static boolean readModule(final Module module, final JsonObject json) {
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

        if (!json.has(ENABLED)) {
            return false;
        }

        JsonElement enabled = json.get(ENABLED);
        if (enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean()) {
            module.setEnabled(enabled.getAsBoolean());
            return true;
        }

        LOGGER.warn("{}: 'enabled' is not a boolean, leaving the module {}", module.getName(),
                module.isEnabledByDefault() ? "on, its default" : "off");
        return false;
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
