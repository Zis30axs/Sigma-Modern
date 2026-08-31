package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/** Creates the title/main-menu presentation selected in Sigma's persisted client mode. */
public final class MainMenuRouter {

    private MainMenuRouter() {
    }

    public static Screen createSelected() {
        return switch (Client.getInstance().getClientModeManager().get()) {
            case JELLO -> new JelloMainMenuScreen();
            case CLASSIC -> new ClassicMainMenuScreen();
            case NO_ADDONS -> new TitleScreen();
        };
    }

    public static void openSelected() {
        Minecraft.getInstance().gui.setScreen(createSelected());
    }
}
