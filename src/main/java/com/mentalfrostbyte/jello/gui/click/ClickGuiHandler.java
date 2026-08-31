package com.mentalfrostbyte.jello.gui.click;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.impl.game.action.EventKeyPress;
import com.mentalfrostbyte.jello.gui.ClientMode;
import com.mentalfrostbyte.jello.gui.SigmaClickGui;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/** Global hotkey for the ClickGUI. */
public final class ClickGuiHandler {

    public static final InputConstants.Key OPEN_KEY = InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_RSHIFT);

    @Nullable
    private static Screen previousScreen;

    private ClickGuiHandler() {
    }

    /** Handles the global ClickGUI hotkey. Returns {@code true} if the key was consumed. */
    public static boolean handleKey(final InputConstants.Key key, final EventKeyPress.Action action) {
        if (action != EventKeyPress.Action.PRESS || !key.equals(OPEN_KEY)) {
            return false;
        }

        Client client = Client.getInstance();
        if (!client.isStarted() || client.getClientModeManager().get() == ClientMode.NO_ADDONS) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof SigmaClickGui) {
            close();
        } else {
            previousScreen = mc.gui.screen();
            mc.gui.setScreen(client.getPresentationManager().createClickGui(client.getModuleManager()));
        }

        return true;
    }

    /** Closes the ClickGUI and restores the screen that was open before it, if any. */
    public static void close() {
        Minecraft mc = Minecraft.getInstance();
        if (Client.getInstance().isStarted()) {
            Client.getInstance().saveConfig();
        }

        mc.gui.setScreen(previousScreen);
        previousScreen = null;
    }
}
