package com.mentalfrostbyte.jello.gui.click;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.impl.game.action.EventKeyPress;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Global hotkey for the ClickGUI.
 *
 * <p>This is called directly from {@link net.minecraft.client.KeyboardHandler} rather than through the
 * no-screen-only {@link EventKeyPress} bus, so Right Shift can open the GUI from the title screen, the
 * pause menu, or any other screen as well as from normal gameplay.</p>
 */
public final class ClickGuiHandler {

    /**
     * Change this constant to bind ClickGUI to a different key.
     * Note: {@code InputConstants.KEY_N} is an {@code int}; wrap it as a Key, e.g.
     * {@code InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_N)}.
     */
    public static final InputConstants.Key OPEN_KEY = InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_RSHIFT);

    @Nullable
    private static Screen previousScreen;

    private ClickGuiHandler() {
    }

    /**
     * Handles the global ClickGUI hotkey. Returns {@code true} if the key was consumed.
     */
    public static boolean handleKey(final InputConstants.Key key, final EventKeyPress.Action action) {
        if (action != EventKeyPress.Action.PRESS || !key.equals(OPEN_KEY)) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof ClickGuiScreen) {
            close();
        } else {
            previousScreen = mc.gui.screen();
            mc.gui.setScreen(new ClickGuiScreen(Client.getInstance().getModuleManager()));
        }

        return true;
    }

    /**
     * Closes the ClickGUI and restores the screen that was open before it, if any.
     */
    public static void close() {
        Minecraft mc = Minecraft.getInstance();
        if (Client.getInstance().isStarted()) {
            Client.getInstance().saveConfig();
        }

        mc.gui.setScreen(previousScreen);
        previousScreen = null;
    }
}
