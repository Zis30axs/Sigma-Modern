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
import com.mentalfrostbyte.jello.gui.mainmenu.MainMenuRouter;
import com.mentalfrostbyte.jello.input.KeybindHandler;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.util.game.MinecraftInstance;
import com.mentalfrostbyte.jello.util.io.JsonFileUtil;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client itself: one instance, created on demand, started once the game has finished loading and shut
 * down with it.
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

    /** Called once from {@code Minecraft.onGameLoadFinished}. */
    public void start() {
        if (this.started) {
            return;
        }

        this.started = true;
        logger.info("Starting {} {} for Minecraft {}", NAME, FULL_VERSION, mc.getLaunchedVersion());
        this.config = JsonFileUtil.read(this.getConfigFile());
        boolean hasClientMode = this.config.has("clientMode");
        this.clientModeManager.read(this.config);

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

        // Sigma's mode is a title-screen presentation choice, not an in-game ClickGUI option. When the
        // normal initial title screen is showing, a saved mode goes straight to its own main menu. A fresh
        // config must choose once before a main menu is shown.
        if (mc.gui.screen() instanceof TitleScreen) {
            if (hasClientMode) {
                MainMenuRouter.openSelected();
            } else {
                logger.info("Opening first-run client mode selection");
                mc.gui.setScreen(new ModeSelectScreen(null, true));
            }
        }

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

    public Path getDirectory() {
        return this.directory;
    }

    private Path getConfigFile() {
        return this.directory.resolve("config.json");
    }
}
