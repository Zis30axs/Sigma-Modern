package com.mentalfrostbyte;

import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.account.SigmaAccountManager;
import com.mentalfrostbyte.jello.config.ModuleConfig;
import com.mentalfrostbyte.jello.event.EventBus;
import com.mentalfrostbyte.jello.gui.ClientMode;
import com.mentalfrostbyte.jello.gui.ClientModeManager;
import com.mentalfrostbyte.jello.gui.GuiInteractionSmoke;
import com.mentalfrostbyte.jello.gui.GuiScreenInteractionSmoke;
import com.mentalfrostbyte.jello.gui.ModeSelectScreen;
import com.mentalfrostbyte.jello.gui.PresentationManager;
import com.mentalfrostbyte.jello.gui.mainmenu.MainMenuRedirectHandler;
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

/** The Sigma client root: lifecycle, persistent config and the single module registry. */
public class Client implements MinecraftInstance {

    public static final Logger logger = LoggerFactory.getLogger("Sigma");
    public static final String NAME = "Sigma";
    public static final String RELEASE_TARGET = "5.1.1";
    public static final int BETA_ITERATION = 16;
    public static final String FULL_VERSION = RELEASE_TARGET + (BETA_ITERATION > 0 ? "b" + BETA_ITERATION : "");

    private static final Client INSTANCE = new Client();

    private final Path directory;
    private final SigmaAccountManager accountManager;
    private final ModuleManager moduleManager = new ModuleManager();
    private final KeybindHandler keybindHandler = new KeybindHandler(this.moduleManager);
    private final MainMenuRedirectHandler mainMenuRedirectHandler = new MainMenuRedirectHandler();
    private final ClientModeManager clientModeManager = new ClientModeManager();
    private final PresentationManager presentationManager = new PresentationManager(this.clientModeManager);

    private JsonObject config = new JsonObject();
    private boolean modulesRegistered;
    private boolean started;

    private Client() {
        this.directory = mc.gameDirectory.toPath().resolve("sigma5");
        this.accountManager = new SigmaAccountManager(this.directory.resolve("accounts.json"));
    }

    public static Client getInstance() {
        return INSTANCE;
    }

    /**
     * Called once from {@code Minecraft.onGameLoadFinished}.
     *
     * <p>{@code started} only becomes {@code true} once every step below has completed - it means
     * "startup finished", not "startup began". If any step throws, {@link #rollbackFailedStart(Throwable)}
     * unregisters the global handlers and disables whatever modules {@link ModuleConfig#read} had already
     * switched on, then the original failure is rethrown so it is not silently lost. {@code started}
     * stays {@code false}, which is what makes a later retry of this method legitimate instead of a no-op.</p>
     */
    public void start() {
        if (this.started) {
            return;
        }

        logger.info("Starting {} {} for Minecraft {}", NAME, FULL_VERSION, mc.getLaunchedVersion());
        try {
            this.config = JsonFileUtil.read(this.getConfigFile());
            this.accountManager.load();
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
            EventBus.register(this.mainMenuRedirectHandler);

            // The mode is a title-screen presentation choice, not an in-game ClickGUI setting. A saved
            // mode goes straight to its main menu; a fresh config chooses once before any Sigma main
            // menu appears.
            if (mc.gui.screen() instanceof TitleScreen) {
                if (hasClientMode) {
                    MainMenuRouter.openSelected();
                } else {
                    logger.info("Opening first-run client mode selection");
                    mc.gui.setScreen(new ModeSelectScreen(null, true));
                }
            }

            this.openDebugGuiIfRequested();
        } catch (final RuntimeException failure) {
            logger.error("Startup failed, rolling back", failure);
            this.rollbackFailedStart();
            throw failure;
        }

        this.started = true;
        logger.info("Started with {} modules.", this.moduleManager.all().size());
    }

    /**
     * Best-effort cleanup after a failed {@link #start()}: unregisters the two global handlers
     * (unregistering an object that was never registered is a no-op) and disables any module that
     * {@link ModuleConfig#read} had already switched on before the failure. One module's cleanup failing
     * must not stop the rest, so each is isolated and logged rather than left enabled.
     *
     * <p>{@code modulesRegistered} is deliberately left as-is: the module instances themselves stay valid
     * and registered in {@link ModuleManager} even after a failed startup, so a retry does not re-run
     * {@link ModuleManager#registerAll()} and cannot hit a duplicate-registration error from that. The one
     * case this does not cover - {@code registerAll()} itself throwing partway through, from a duplicate
     * class or name in the hardcoded list - is a programming error rather than a runtime condition, and
     * is deliberately not retryable; fixing the list is the correct response, not a rollback.</p>
     */
    private void rollbackFailedStart() {
        EventBus.unregister(this.mainMenuRedirectHandler);
        EventBus.unregister(this.keybindHandler);
        for (Module module : this.moduleManager.all()) {
            if (module.isEnabled()) {
                try {
                    module.setEnabled(false);
                } catch (final RuntimeException cleanupFailure) {
                    logger.error("Could not disable {} while rolling back a failed startup", module.getName(), cleanupFailure);
                }
            }
        }
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

    /**
     * Called once while the game is tearing down, before the window goes away.
     *
     * <p>A broken module or a config save failure must not stop the rest of teardown: every enabled
     * module gets an isolated attempt at {@link Module#setEnabled(boolean)}, the global handlers are
     * always unregistered, and {@code started} is always cleared in a {@code finally} so a second call
     * to this method - or a later {@link #start()} - sees a consistent, defined state regardless of what
     * failed. Every failure is logged; the first one is rethrown once cleanup is complete, with any
     * further failures attached to it as suppressed, so a caller that cares can still see something went
     * wrong without that visibility coming at the cost of incomplete teardown.</p>
     */
    public void shutdown() {
        if (!this.started) {
            return;
        }

        logger.info("Shutting down...");
        RuntimeException failure = null;
        try {
            this.saveConfig();
        } catch (final RuntimeException saveFailure) {
            // Config save failing must not block teardown - the log is the only trace if nothing below
            // also fails.
            logger.error("Could not save the config while shutting down", saveFailure);
            failure = saveFailure;
        }

        try {
            EventBus.unregister(this.mainMenuRedirectHandler);
            EventBus.unregister(this.keybindHandler);

            for (Module module : this.moduleManager.all()) {
                if (!module.isEnabled()) {
                    continue;
                }
                try {
                    module.setEnabled(false);
                } catch (final RuntimeException moduleFailure) {
                    logger.error("Could not disable {} while shutting down", module.getName(), moduleFailure);
                    if (failure == null) {
                        failure = moduleFailure;
                    } else {
                        failure.addSuppressed(moduleFailure);
                    }
                }
            }
        } finally {
            this.started = false;
            logger.info("Done.");
        }

        if (failure != null) {
            throw failure;
        }
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

    public SigmaAccountManager getAccountManager() {
        return this.accountManager;
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
