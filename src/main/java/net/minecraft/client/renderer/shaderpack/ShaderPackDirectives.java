package net.minecraft.client.renderer.shaderpack;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;

final class ShaderPackDirectives {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FLIP_PREFIX = "flip.";
    private static final List<String> LEGACY_COLOR_TARGETS = List.of(
        "gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"
    );
    private static final ShaderPackDirectives EMPTY = new ShaderPackDirectives(Map.of());
    private final Map<String, Map<Integer, Boolean>> explicitFlips;

    private ShaderPackDirectives(final Map<String, Map<Integer, Boolean>> explicitFlips) {
        this.explicitFlips = explicitFlips;
    }

    static ShaderPackDirectives parse(final ShaderPackSource source) throws IOException {
        Optional<String> propertiesText = source.readText("shaders.properties");
        if (propertiesText.isEmpty()) {
            return EMPTY;
        }

        Properties properties = new Properties();
        properties.load(new StringReader(propertiesText.get()));
        Map<String, Map<Integer, Boolean>> flips = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(FLIP_PREFIX)) {
                continue;
            }

            String suffix = key.substring(FLIP_PREFIX.length());
            int separator = suffix.lastIndexOf('.');
            if (separator <= 0 || separator == suffix.length() - 1) {
                LOGGER.warn("Ignoring malformed shader-pack flip directive {}", key);
                continue;
            }

            String pass = suffix.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            String buffer = suffix.substring(separator + 1).strip().toLowerCase(Locale.ROOT);
            Integer target = colorTarget(buffer);
            if (target == null) {
                LOGGER.warn("Ignoring shader-pack flip directive {} because {} is not a supported color target", key, buffer);
                continue;
            }

            String rawValue = properties.getProperty(key, "").strip();
            Boolean shouldFlip = switch (rawValue.toLowerCase(Locale.ROOT)) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> null;
            };
            if (shouldFlip == null) {
                LOGGER.warn("Ignoring shader-pack flip directive {} because {} is not true or false", key, rawValue);
                continue;
            }

            flips.computeIfAbsent(pass, ignored -> new LinkedHashMap<>()).put(target, shouldFlip);
        }

        Map<String, Map<Integer, Boolean>> immutable = new LinkedHashMap<>();
        flips.forEach((pass, values) -> immutable.put(pass, Map.copyOf(values)));
        return immutable.isEmpty() ? EMPTY : new ShaderPackDirectives(Map.copyOf(immutable));
    }

    Map<Integer, Boolean> explicitFlips(final String pass) {
        return this.explicitFlips.getOrDefault(pass.toLowerCase(Locale.ROOT), Map.of());
    }

    private static Integer colorTarget(final String name) {
        int legacy = LEGACY_COLOR_TARGETS.indexOf(name);
        if (legacy >= 0) {
            return legacy;
        }
        if (!name.startsWith("colortex")) {
            return null;
        }
        try {
            int target = Integer.parseInt(name.substring("colortex".length()));
            return target >= 0 && target < ShaderPackRenderTargets.COLOR_TARGET_COUNT ? target : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
