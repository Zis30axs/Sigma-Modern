package com.mentalfrostbyte.jello.module.impl.combat;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** One UTF-8 JSON line per event. The sequence is ordered on the game thread. */
final class RotationTestLog implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private @Nullable BufferedWriter writer;
    private int buffered;

    Path open(Path gameDirectory) throws IOException {
        close();
        Path directory = Files.createDirectories(gameDirectory.resolve("logs").resolve("rotation-test"));
        Path file = Files.createTempFile(directory, "visible-" + STAMP.format(Instant.now()) + "-", ".jsonl");
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        buffered = 0;
        JsonObject start = event("start", 0);
        start.addProperty("schema", 1);
        start.addProperty("rotation", "VISIBLE_TEST");
        start.addProperty("probeVersion", 1);
        start.addProperty("sampleBudget", VisiblePointScan.BUDGET);
        start.addProperty("startedUtc", Instant.now().toString());
        write(start, true);
        if (writer == null) throw new IOException("Could not write the rotation test header");
        return file;
    }

    static JsonObject event(String name, long tick) {
        JsonObject row = new JsonObject();
        row.addProperty("event", name);
        row.addProperty("tick", tick);
        return row;
    }

    void write(JsonObject row, boolean flush) {
        if (writer == null) return;
        try {
            writer.write(row.toString());
            writer.newLine();
            buffered++;
            if (flush || buffered >= 20) {
                writer.flush();
                buffered = 0;
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write the visible rotation test log", e);
            close();
        }
    }

    @Override public void close() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException e) {
            LOGGER.warn("Could not close the visible rotation test log", e);
        } finally {
            writer = null;
        }
    }
}
