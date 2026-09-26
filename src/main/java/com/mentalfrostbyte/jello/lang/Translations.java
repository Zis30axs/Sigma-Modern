package com.mentalfrostbyte.jello.lang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's own text in the {@linkplain ClientLanguage language} the user picked.
 *
 * <p>A key is looked up in the picked language, then in English, and failing both it reads as itself - a missing
 * translation shows English, and a key nobody wrote yet shows up on screen instead of crashing it. Files are read once
 * each, the first time they're needed.</p>
 *
 * <p>The choice is stored in the config as {@code "language": "<code>"}; a code this client doesn't know leaves the
 * current language, and a file that can't be read counts as empty, both with a warning.</p>
 */
public final class Translations {

    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Lang");

    private static final String CONFIG_KEY = "language";

    private final Function<ClientLanguage, Map<String, String>> source;

    private final Map<ClientLanguage, Map<String, String>> loaded = new EnumMap<>(ClientLanguage.class);

    private ClientLanguage selected = ClientLanguage.SOURCE;

    /** Reads the files bundled with the client. */
    public Translations() {
        this(Translations::readBundled);
    }

    /** Reads each language's strings from {@code source}; for tests. */
    public Translations(final Function<ClientLanguage, Map<String, String>> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public ClientLanguage selected() {
        return this.selected;
    }

    public void select(final ClientLanguage language) {
        this.selected = Objects.requireNonNull(language, "language");
    }

    /** {@code key}'s text in the picked language, else in English, else the key itself. */
    public String get(final String key) {
        String text = this.strings(this.selected).get(key);
        if (text == null && this.selected != ClientLanguage.SOURCE) {
            text = this.strings(ClientLanguage.SOURCE).get(key);
        }

        return text == null ? key : text;
    }

    /**
     * {@link #get(String)} with {@code %s}-style placeholders filled in. A translation whose placeholders don't fit
     * the arguments falls back to the English text rather than throwing mid-frame.
     */
    public String get(final String key, final Object... args) {
        try {
            return String.format(Locale.ROOT, this.get(key), args);
        } catch (IllegalFormatException badTranslation) {
            String english = this.strings(ClientLanguage.SOURCE).getOrDefault(key, key);
            try {
                return String.format(Locale.ROOT, english, args);
            } catch (IllegalFormatException badSource) {
                return english;
            }
        }
    }

    /** Every key and its text in {@code language}'s own file (without the English fallback). */
    public Map<String, String> strings(final ClientLanguage language) {
        return this.loaded.computeIfAbsent(language, this.source);
    }

    public void read(final JsonObject config) {
        if (!config.has(CONFIG_KEY) || !config.get(CONFIG_KEY).isJsonPrimitive()) {
            return;
        }

        String code = config.get(CONFIG_KEY).getAsString();
        ClientLanguage language = ClientLanguage.byCode(code);
        if (language == null) {
            LOGGER.warn("Config asks for language '{}', which this client doesn't have - keeping {}", code, this.selected.code());
            return;
        }

        this.selected = language;
    }

    public void write(final JsonObject config) {
        config.addProperty(CONFIG_KEY, this.selected.code());
    }

    /** A language file's strings: a flat JSON object of key to text. Anything else in it is skipped with a warning. */
    public static Map<String, String> parse(final Reader reader, final String name) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            LOGGER.warn("{} is not a JSON object - ignoring it", name);
            return Map.of();
        }

        Map<String, String> strings = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                strings.put(entry.getKey(), value.getAsString());
            } else {
                LOGGER.warn("{}: '{}' is not a string - skipping it", name, entry.getKey());
            }
        }

        return Collections.unmodifiableMap(strings);
    }

    private static Map<String, String> readBundled(final ClientLanguage language) {
        String resource = language.langResource();
        try (@Nullable InputStream stream = Translations.class.getResourceAsStream(resource)) {
            if (stream == null) {
                LOGGER.warn("No language file {} - its text will be English", resource);
                return Map.of();
            }

            return parse(new InputStreamReader(stream, StandardCharsets.UTF_8), resource);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not read {} - its text will be English", resource, failure);
            return Map.of();
        }
    }
}
