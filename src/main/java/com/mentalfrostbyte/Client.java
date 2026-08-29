package com.mentalfrostbyte;

import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.config.ModuleConfig;
import com.mentalfrostbyte.jello.event.EventBus;
import com.mentalfrostbyte.jello.input.KeybindHandler;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.util.game.MinecraftInstance;
import com.mentalfrostbyte.jello.util.io.JsonFileUtil;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client itself: one instance, created on demand, started once the game has finished loading and shut
 * down with it.
 *
 * <p>It owns the client directory, the persisted config and the module registry, and exists so the layers
 * that follow - the interface, the managers the features need - have a single place to hang off. It
 * deliberately does not hold the wall of managers the 1.16 client kept here; those come back one at a time
 * as they are ported, rather than all having to exist before the game can boot.</p>
 */
public class Client implements MinecraftInstance {

    public static final Logger logger = LoggerFactory.getLogger("Sigma");

    public static final String NAME = "Sigma";

    public static final String RELEASE_TARGET = "5.1.1";

    public static final int BETA_ITERATION = 16;

    public static final String FULL_VERSION = RELEASE_TARGET + (BETA_ITERATION > 0 ? "b" + BETA_ITERATION : "");

    private static final Client INSTANCE = new Client();

    private final Path directory;

    private final ModuleManager moduleManager = new ModuleManager();

    private JsonObject config = new JsonObject();

    private boolean started;

    private Client() {
        this.directory = mc.gameDirectory.toPath().resolve("sigma5");
    }

    public static Client getInstance() {
        return INSTANCE;
    }

    /**
     * Called once from {@code Minecraft.onGameLoadFinished}, so the window, the options and the
     * {@code Minecraft} singleton all exist by the time anything here runs.
     */
    public void start() {
        if (this.started) {
            return;
        }

        this.started = true;
        logger.info("Starting {} {} for Minecraft {}", NAME, FULL_VERSION, mc.getLaunchedVersion());
        this.config = JsonFileUtil.read(this.getConfigFile());
        this.moduleManager.registerAll();
        ModuleConfig.read(this.config, this.moduleManager);
        EventBus.register(new KeybindHandler(this.moduleManager));
        logger.info("Started with {} modules.", this.moduleManager.all().size());
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

    public boolean isStarted() {
        return this.started;
    }

    /**
     * Collects the current state and writes the config back to disk. A failure is logged and swallowed -
     * this runs on the shutdown path, where throwing would turn a lost config into a crash report.
     */
    public void saveConfig() {
        ModuleConfig.write(this.config, this.moduleManager);
        try {
            JsonFileUtil.write(this.getConfigFile(), this.config);
        } catch (IOException failure) {
            logger.error("Could not save the config", failure);
        }
    }

    public ModuleManager getModuleManager() {
        return this.moduleManager;
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
