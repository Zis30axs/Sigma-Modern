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
    // NetEase Cloud Music (streams, search, charts) with QQ Music for word-timed lyrics. -Dsigma.debug.musicOffline
    // keeps the built-in demo list on a silent, time-keeping backend instead: no network, deterministic for tests.
    private final boolean musicOffline = Boolean.getBoolean("sigma.debug.musicOffline");
    private final com.mentalfrostbyte.jello.music.MusicLibrary musicLibrary;
    private final com.mentalfrostbyte.jello.music.MusicPlayer musicPlayer;
    // Feeds module toggles to SigmaModern's in-game island; it only records them, drawing decides what shows.
    private final Object islandActivity = new com.mentalfrostbyte.jello.gui.modern.ModernIsland.ActivityListener();
    // The music's effects settings, and whether the player's head is under water (the audio muffles itself then).
    private final com.mentalfrostbyte.jello.music.MusicEffects musicEffects = new com.mentalfrostbyte.jello.music.MusicEffects();
    private final Object musicSubmerged = new com.mentalfrostbyte.jello.gui.modern.ModernMusicFx.SubmergedListener(this.musicEffects);

    private JsonObject config = new JsonObject();
    private boolean modulesRegistered;
    private boolean started;
    // -1 means "no screenshot pending"; set by -Dsigma.debug.screenshotAfterFrames so the capture happens
    // a few real frames after the debug GUI opens, instead of the still-blank frame open() runs on.
    private int screenshotFramesRemaining = -1;
    // -Dsigma.debug.openGuiInWorld: open the ClickGUI once a world has loaded (e.g. after --quickPlaySingleplayer),
    // so it can be checked over a real world rather than only over the no-world background.
    private boolean openGuiInWorldPending = Boolean.getBoolean("sigma.debug.openGuiInWorld");

    private Client() {
        this.directory = mc.gameDirectory.toPath().resolve("sigma5");
        this.accountManager = new SigmaAccountManager(this.directory.resolve("accounts.json"));
        if (this.musicOffline) {
            this.musicLibrary = new com.mentalfrostbyte.jello.music.MusicLibrary(null);
            this.musicPlayer = new com.mentalfrostbyte.jello.music.MusicPlayer(
                new com.mentalfrostbyte.jello.music.SilentBackend(System::nanoTime), new com.mentalfrostbyte.jello.music.DemoMusicSource());
        } else {
            // No network here: the session only reads (or creates) its device and cookie files.
            com.mentalfrostbyte.jello.music.netease.NeteaseApi netease = new com.mentalfrostbyte.jello.music.netease.NeteaseApi(
                new com.mentalfrostbyte.jello.music.netease.NeteaseSession(this.directory));
            this.musicLibrary = new com.mentalfrostbyte.jello.music.MusicLibrary(netease);
            // -Dsigma.debug.musicMuted: stream and keep time as usual but output silence (captures of real playback);
            // unlike setting the volume, nothing of it reaches the saved config.
            boolean muted = Boolean.getBoolean("sigma.debug.musicMuted");
            this.musicPlayer = new com.mentalfrostbyte.jello.music.MusicPlayer(
                new com.mentalfrostbyte.jello.music.StreamingBackend(track -> {
                    com.mentalfrostbyte.jello.music.netease.NeteaseApi.Stream stream =
                        netease.stream(com.mentalfrostbyte.jello.music.netease.NeteaseApi.songId(track));
                    return stream == null ? null : new com.mentalfrostbyte.jello.music.StreamingBackend.Resolved(stream.url(), stream.trialEndMs());
                }, () -> muted ? 0.0 : mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER), this.musicEffects),
                new com.mentalfrostbyte.jello.music.ListSource("网易云 · 热歌榜", java.util.List.of()));
            // A QR login can make a track that was only a 30 s preview playable in full: load it again (the player
            // belongs to the main thread; the login finishes on its own).
            com.mentalfrostbyte.jello.music.MusicPlayer player = this.musicPlayer;
            this.musicLibrary.account().onSignedIn(() -> mc.execute(() -> {
                if (player.isPreview()) player.reloadCurrent();
            }));
        }
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
            this.musicPlayer.read(this.config);
            this.musicLibrary.read(this.config);
            this.musicEffects.read(this.config);
            this.applyDebugClientModeIfRequested();
            if (Boolean.getBoolean("sigma.debug.logMode")) {
                logger.info("Sigma debug: clientMode={}", this.clientModeManager.get());
            }
            EventBus.register(this.keybindHandler);
            EventBus.register(this.mainMenuRedirectHandler);
            EventBus.register(this.musicPlayer);
            EventBus.register(this.islandActivity);
            EventBus.register(this.musicSubmerged);
            // -Dsigma.debug.musicPreview: start the (silent) player a third of the way in, so captures show it playing.
            if (Boolean.getBoolean("sigma.debug.musicPreview")) {
                this.musicPlayer.play();
                this.musicPlayer.seekFraction(0.35F);
            }
            // -Dsigma.debug.musicSearch=<query>: queue that search's results (and with -Dsigma.debug.musicAutoplay play
            // the first), so the island and the window can be captured with a real track.
            String debugSearch = System.getProperty("sigma.debug.musicSearch");
            if (debugSearch != null && !debugSearch.isBlank() && this.musicLibrary.isOnline()) {
                boolean autoplay = Boolean.getBoolean("sigma.debug.musicAutoplay");
                this.musicLibrary.search(debugSearch.strip()).thenAccept(source -> mc.execute(() -> {
                    if (!source.tracks().isEmpty()) this.musicPlayer.setSource(source, 0, autoplay);
                }));
            }

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
            this.openDebugScreenIfRequested();
            // Armed independently of openGui, so a plain launch that lands on the main menu can be captured too;
            // an in-world GUI arms its own countdown once it actually opens.
            if (!this.openGuiInWorldPending) this.armDebugScreenshot();
        } catch (final RuntimeException failure) {
            logger.error("Startup failed, rolling back", failure);
            this.rollbackFailedStart();
            throw failure;
        }

        this.started = true;
        logger.info("Started with {} modules.", this.moduleManager.all().size());
    }

    /**
     * Best-effort cleanup after a failed {@link #start()}: unregisters the global listeners
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
        EventBus.unregister(this.islandActivity);
        EventBus.unregister(this.musicSubmerged);
        EventBus.unregister(this.musicPlayer);
        this.musicPlayer.pause();
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
                String previewView = System.getProperty("sigma.debug.modernPreviewView");
                if (previewView != null && mc.gui.screen() instanceof com.mentalfrostbyte.jello.gui.modern.ModernClickGuiScreen modernScreen) {
                    modernScreen.debugPreview(previewView);
                }
                return;
            }
        }

        logger.warn("Sigma debug: unknown GUI mode '{}'", requested);
    }

    /**
     * {@code -Dsigma.debug.openScreen=WORLDS|SERVERS|OPTIONS|PROTOCOL|MODES|SOUND}: opens the vanilla world list, server
     * list, options hub, ViaFabricPlus version picker or a plain vanilla sub-page through the normal
     * {@code setScreen} route, so a presentation that replaces those screens can be captured without clicking
     * through the menu. Over the selected main menu at startup - or, with {@code -Dsigma.debug.openGuiInWorld},
     * once a world has loaded (with no parent, so closing it returns to the game).
     */
    private void openDebugScreenIfRequested() {
        // -Dsigma.debug.openScreenDelayFrames=<n> opens it from the main menu n frames later instead, e.g. to
        // leave a presentation's window chrome installed before switching away from it.
        if (!this.openGuiInWorldPending && this.openScreenDelay < 0) this.openDebugScreen(MainMenuRouter.createSelected());
    }

    private int openScreenDelay = Integer.getInteger("sigma.debug.openScreenDelayFrames", -1);

    private boolean openDebugScreen(final net.minecraft.client.gui.screens.Screen parent) {
        String requested = System.getProperty("sigma.debug.openScreen");
        if (requested == null || requested.isBlank()) {
            return false;
        }

        net.minecraft.client.gui.screens.Screen options = new net.minecraft.client.gui.screens.options.OptionsScreen(parent, mc.options, mc.level != null);
        net.minecraft.client.gui.screens.Screen screen = switch (requested.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "WORLDS" -> new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(parent);
            case "SERVERS" -> new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(parent);
            case "OPTIONS" -> options;
            // Through VFP's own singleton, so the router sees exactly what a real "open" hands it.
            case "PROTOCOL" -> com.viaversion.viafabricplus.screen.impl.ProtocolSelectionScreen.INSTANCE.get(parent);
            case "MODES" -> new ModeSelectScreen(parent);
            // A plain vanilla sub-page, for checking how a presentation skins vanilla widgets.
            case "SOUND" -> new net.minecraft.client.gui.screens.options.SoundOptionsScreen(options, mc.options);
            default -> null;
        };
        if (screen == null) {
            logger.warn("Sigma debug: unknown screen '{}'", requested);
            return false;
        }
        mc.gui.setScreen(screen);
        logger.info("Sigma debug: opened {} as {}", requested, mc.gui.screen().getClass().getSimpleName());
        return true;
    }

    /**
     * Called once per rendered GUI frame from {@code Gui.extractRenderState}. Only does anything while a
     * {@code -Dsigma.debug.screenshotAfterFrames} countdown is pending, so this is free the rest of the
     * time. Lets a debug run capture what the currently open Sigma screen actually looks like, rather than
     * only exercising its input handlers.
     */
    // -Dsigma.debug.maximizeAfterFrames=<n>: maximize the window after n frames, restore it n frames later, and
    // log the slowest frames around each change - for measuring resize hitches without touching the mouse.
    private int maximizeCountdown = Integer.getInteger("sigma.debug.maximizeAfterFrames", -1);
    private int maximizePhase;
    private long lastFrameNanos;
    private int timingFramesLeft;

    public void onGuiFrameRendered() {
        long frameNow = System.nanoTime();
        if (this.timingFramesLeft > 0) {
            long gap = (frameNow - this.lastFrameNanos) / 1_000_000;
            if (gap > 25) logger.info("Sigma debug: slow frame {} ms (fb {}x{})", gap, mc.getWindow().getWidth(), mc.getWindow().getHeight());
            this.timingFramesLeft--;
        }
        this.lastFrameNanos = frameNow;
        if (this.openScreenDelay > 0 && --this.openScreenDelay == 0) {
            mc.execute(() -> this.openDebugScreen(mc.gui.screen()));
        }
        if (this.maximizeCountdown > 0 && --this.maximizeCountdown == 0 && this.maximizePhase < 4) {
            long handle = mc.getWindow().handle();
            long callStart = System.nanoTime();
            if (this.maximizePhase % 2 == 0) org.lwjgl.glfw.GLFW.glfwMaximizeWindow(handle);
            else org.lwjgl.glfw.GLFW.glfwRestoreWindow(handle);
            logger.info("Sigma debug: {} window (the call itself took {} ms)", this.maximizePhase % 2 == 0 ? "maximized" : "restored",
                (System.nanoTime() - callStart) / 1_000_000);
            this.maximizePhase++;
            this.maximizeCountdown = Integer.getInteger("sigma.debug.maximizeAfterFrames", -1);
            this.timingFramesLeft = 40;
        }
        if (this.openGuiInWorldPending && mc.level != null && mc.player != null && mc.gui.screen() == null) {
            this.openGuiInWorldPending = false;
            logger.info("Sigma debug: in-world GUI opening, player facing {} (yRot {})", mc.player.getDirection(), mc.player.getYRot());
            // Deferred: this runs at the end of GUI extraction, not a place to swap screens.
            mc.execute(() -> {
                if (this.openDebugScreen(null)) return;
                mc.gui.setScreen(this.presentationManager.createClickGui(this.moduleManager));
                String previewView = System.getProperty("sigma.debug.modernPreviewView");
                if (previewView != null && mc.gui.screen() instanceof com.mentalfrostbyte.jello.gui.modern.ModernClickGuiScreen modernScreen) {
                    modernScreen.debugPreview(previewView);
                }
            });
            this.armDebugScreenshot();
        }
        if (this.screenshotFramesRemaining < 0) {
            return;
        }
        if (this.screenshotFramesRemaining > 0) {
            this.screenshotFramesRemaining--;
            return;
        }
        this.screenshotFramesRemaining = -1;
        net.minecraft.client.Screenshot.grab(
            mc.gameDirectory, "sigma-debug.png", mc.gameRenderer.mainRenderTarget(), 1,
            message -> logger.info("Sigma debug: {}", message.getString())
        );
    }

    private void armDebugScreenshot() {
        String screenshotAfter = System.getProperty("sigma.debug.screenshotAfterFrames");
        if (screenshotAfter != null && !screenshotAfter.isBlank()) {
            this.screenshotFramesRemaining = Integer.parseInt(screenshotAfter.trim());
        }
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
            EventBus.unregister(this.islandActivity);
            EventBus.unregister(this.musicSubmerged);
            EventBus.unregister(this.musicPlayer);
            // Stops the audio line and the download/decode threads.
            this.musicPlayer.close();
            this.musicLibrary.close();
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
        this.musicPlayer.write(this.config);
        this.musicLibrary.write(this.config);
        this.musicEffects.write(this.config);
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

    public com.mentalfrostbyte.jello.music.MusicPlayer getMusicPlayer() {
        return this.musicPlayer;
    }

    public com.mentalfrostbyte.jello.music.MusicEffects getMusicEffects() {
        return this.musicEffects;
    }

    public com.mentalfrostbyte.jello.music.MusicLibrary getMusicLibrary() {
        return this.musicLibrary;
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
