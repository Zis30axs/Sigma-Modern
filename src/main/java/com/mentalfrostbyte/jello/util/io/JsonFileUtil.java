package com.mentalfrostbyte.jello.util.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reading and writing the client's JSON files.
 *
 * <p>A missing, empty or corrupt file reads back as an empty object rather than throwing: a damaged
 * config should cost the user their settings, not their game. Writes create the parent directory and
 * go through a temporary file, so an interrupted save cannot leave a half-written config behind.</p>
 */
public final class JsonFileUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonFileUtil() {
    }

    public static JsonObject read(final Path file) {
        if (!Files.isRegularFile(file)) {
            return new JsonObject();
        }

        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            LOGGER.error("Could not read {}", file, failure);
            return new JsonObject();
        }

        if (content.isBlank()) {
            return new JsonObject();
        }

        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }

            LOGGER.warn("{} does not contain a JSON object, ignoring it", file);
        } catch (JsonParseException failure) {
            LOGGER.warn("{} is not valid JSON, ignoring it", file, failure);
        }

        return new JsonObject();
    }

    public static void write(final Path file, final JsonObject content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(content), StandardCharsets.UTF_8);
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
