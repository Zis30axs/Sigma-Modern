package com.mentalfrostbyte;

import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.config.ModuleConfig;
import com.mentalfrostbyte.jello.event.EventBus;
import com.mentalfrostbyte.jello.gui.ClientMode;
import com.mentalfrostbyte.jello.gui.ClientModeManager;
import com.mentalfrostbyte.jello.gui.GuiInteractionSmoke;
import com.mentalfrostbyte.jello.gui.GuiScreenInteractionSmoke;
import com.mentalfrostbyte.jello.gui.ModeSelectScreen;
import com.mentalfrostbyte.jello.gui.PresentationManager;
import com.mentalfrostbyte.jello.input.KeybindHandler;
import com.mentalfrostbyte.jello.module.Module;
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

    private final KeybindHandler keybindHandler = new KeybindHandler(this.moduleManager);

    private final ClientModeManager clientModeManager = new ClientModeManager();

    private final PresentationManager presentationManager = new PresentationManager(this.clientModeManager);

    private JsonObject config = new JsonObject();

    private boolean modulesRegistered;

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
        this.clientModeManager.read(this.config);
        if (!this.config.has("clientMode")) {
            // This runs during Minecraft construction, before the first frame is rendered, so the mode
            // selection replaces the main menu before it is ever shown to the user.
            logger.info("Sigma debug: opening first-run mode select");
            mc.gui.setScreen(new ModeSelectScreen(mc.gui.screen(), true));
        }
        if (!this.modulesRegistered) {
            this.moduleManager.registerAll();
            this.modulesRegistered = true;
        }
        ModuleConfig.read(this.config, this.moduleManager);
        this.applyDebugClientModeIfRequested();
        if (Boolean.getBoolean("sigma.debug.logMode")) {
            logger.info("Sigma debug: clientMode={}", this.clientModeManager.get());
        }
        EventBus.register(this.keybindHandler);
        logger.info("Started with {} modules.", this.moduleManager.all().size());
        this.openDebugGuiIfRequested();
    }

    private void applyDebugClientModeIfRequested() {
        String requested = System.getProperty("sigma.debug.setClientMode");
        if (requested == null || requested.isBlank()) {
            return;
        }

        for (ClientMode mode : ClientMode.values()) {
            if (mode.name().equalsIgnoreCase(requested)) {
                this.clientModeManager.set(mode);
                this.saveConfig();
                logger.info("Sigma debug: set clientMode={}", mode);
                return;
            }
        }

        logger.warn("Sigma debug: unknown clientMode '{}'", requested);
    }

    private void openDebugGuiIfRequested() {
        String requested = System.getProperty("sigma.debug.openGui");
        if (requested == null || requested.isBlank()) {
            return;
        }

        for (ClientMode mode : ClientMode.values()) {
            if (mode.name().equalsIgnoreCase(requested)) {
                this.clientModeManager.set(mode);
                mc.gui.setScreen(this.presentationManager.createClickGui(this.moduleManager));
                logger.info("Sigma debug: opened {} GUI", mode);
                if (Boolean.getBoolean("sigma.debug.smoke")) {
                    GuiInteractionSmoke.run(this.moduleManager);
                }
                if (Boolean.getBoolean("sigma.debug.screenSmoke")) {
                    GuiScreenInteractionSmoke.run(mc.gui.screen());
                }
                return;
            }
        }

        logger.warn("Sigma debug: unknown GUI mode '{}'", requested);
    }

    /** Called once while the game is tearing down, before the window goes away. */
    public void shutdown() {
        if (!this.started) {
            return;
        }

        logger.info("Shutting down...");
        this.saveConfig();
        EventBus.unregister(this.keybindHandler);
        for (Module module : this.moduleManager.all()) {
            if (module.isEnabled()) {
                module.setEnabled(false);
            }
        }
        this.started = false;
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
        this.clientModeManager.write(this.config);
        try {
            JsonFileUtil.write(this.getConfigFile(), this.config);
        } catch (IOException failure) {
            logger.error("Could not save the config", failure);
        }
    }

    public ModuleManager getModuleManager() {
        return this.moduleManager;
    }

    public ClientModeManager getClientModeManager() {
        return this.clientModeManager;
    }

    public PresentationManager getPresentationManager() {
        return this.presentationManager;
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
