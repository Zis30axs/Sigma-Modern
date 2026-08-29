package com.mentalfrostbyte;

import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.util.game.MinecraftInstance;
import com.mentalfrostbyte.jello.util.io.JsonFileUtil;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client itself: one instance, created on demand, started from the game's constructor and shut down
 * with it.
 *
 * <p>At this stage it owns the client directory and the persisted config, and exists so the layers that
 * follow - modules, settings, the interface - have a single place to hang off. It deliberately does not
 * hold the managers the 1.16 client kept here; those come back one at a time as they are ported, rather
 * than as a wall of fields that must all exist before the game can boot.</p>
 */
public class Client implements MinecraftInstance {

    public static final Logger logger = LoggerFactory.getLogger("Sigma");

    public static final String RELEASE_TARGET = "5.1.1";

    public static final int BETA_ITERATION = 16;

    public static final String FULL_VERSION = RELEASE_TARGET + (BETA_ITERATION > 0 ? "b" + BETA_ITERATION : "");

    private static final Client INSTANCE = new Client();

    private final Path directory;

    private JsonObject config = new JsonObject();

    private boolean started;

    private Client() {
        this.directory = mc.gameDirectory.toPath().resolve("sigma5");
    }

    public static Client getInstance() {
        return INSTANCE;
    }

    /** Called once from the game's constructor, after the window and options exist. */
    public void start() {
        if (this.started) {
            return;
        }

        this.started = true;
        logger.info("Starting Sigma {} for Minecraft {}", FULL_VERSION, mc.getLaunchedVersion());
        this.config = JsonFileUtil.read(this.getConfigFile());
        logger.info("Started.");
    }

    /** Called once while the game is tearing down, before the window goes away. */
    public void shutdown() {
        if (!this.started) {
            return;
        }

        this.started = false;
        logger.info("Shutting down...");
        this.saveConfig();
        logger.info("Done.");
    }

    /**
     * Writes the config back to disk. A failure is logged and swallowed - this runs on the shutdown
     * path, where throwing would turn a lost config into a crash report.
     */
    public void saveConfig() {
        try {
            JsonFileUtil.write(this.getConfigFile(), this.config);
        } catch (IOException failure) {
            logger.error("Could not save the config", failure);
        }
    }

    public JsonObject getConfig() {
        return this.config;
    }

    /** {@code run/sigma5}: everything the client persists lives under here. */
    public Path getDirectory() {
        return this.directory;
    }

    private Path getConfigFile() {
        return this.directory.resolve("config.json");
    }
}
