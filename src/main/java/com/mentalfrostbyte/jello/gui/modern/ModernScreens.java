package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.ClientMode;
import com.viaversion.viafabricplus.screen.impl.PerServerVersionScreen;
import com.viaversion.viafabricplus.screen.impl.ProtocolSelectionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.jspecify.annotations.Nullable;

/**
 * Decides when SigmaModern presents vanilla screens its own way.
 *
 * <p>{@link #route} runs at the top of {@code Gui.setScreen}, so every way into the world list, the server
 * list, the options hub or ViaFabricPlus's version pickers - the Modern main menu, the pause menu, the multiplayer safety notice, a
 * disconnect screen's "back", the server list's own refresh - lands on the Modern version without a
 * one-frame vanilla flash. Only the exact vanilla classes are swapped: subclasses (including the Modern
 * versions themselves) pass through untouched.</p>
 */
public final class ModernScreens {
    private ModernScreens() {}

    /** True while SigmaModern is the active presentation. Safe to call from any render or input path. */
    public static boolean active() {
        Client client = Client.getInstance();
        return client.isStarted() && client.getClientModeManager().get() == ClientMode.SIGMA_MODERN;
    }

    public static @Nullable Screen route(@Nullable Screen screen) {
        if (screen == null) return null;
        // Class checks first: these screens are never opened before Sigma has started, so Client isn't touched
        // (and initialized) for the loading screens that go through setScreen during startup.
        Class<?> type = screen.getClass();
        if (type != SelectWorldScreen.class && type != JoinMultiplayerScreen.class && type != OptionsScreen.class
            && type != ProtocolSelectionScreen.class && type != PerServerVersionScreen.class) return screen;
        // Only the mode matters here (not isStarted()): the client's own startup may already open one of these.
        if (Client.getInstance().getClientModeManager().get() != ClientMode.SIGMA_MODERN) return screen;
        if (screen instanceof SelectWorldScreen worlds) return new ModernWorldsScreen(worlds.getLastScreen());
        if (screen instanceof JoinMultiplayerScreen servers) return new ModernServersScreen(servers.getLastScreen());
        // ViaFabricPlus's version pickers: the global one is a singleton whose parent open() just set.
        if (screen instanceof ProtocolSelectionScreen protocols) return ModernProtocolScreen.global(protocols.prevScreen);
        if (screen instanceof PerServerVersionScreen forced) {
            return ModernProtocolScreen.perServer(forced.prevScreen, forced.selectionConsumer(), forced.selectionSupplier());
        }
        OptionsScreen options = (OptionsScreen) screen;
        return new ModernOptionsScreen(options.getLastScreen(), net.minecraft.client.Minecraft.getInstance().options, options.isInWorld());
    }
}
