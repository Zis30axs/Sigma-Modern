package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.util.game.MinecraftInstance;
import net.minecraft.client.gui.screens.TitleScreen;

/** Keeps the persisted Sigma presentation after vanilla recreates a plain title screen. */
public final class MainMenuRedirectHandler implements MinecraftInstance {

    @EventTarget
    public void onTick(final EventTick event) {
        Client client = Client.getInstance();
        if (!event.isPre() || !client.isStarted()) {
            return;
        }

        // Route only the exact vanilla screen. NoAddonsMainMenuScreen intentionally extends
        // TitleScreen, so instanceof would cause a replacement loop every tick.
        if (mc.level == null && mc.gui.screen() != null && mc.gui.screen().getClass() == TitleScreen.class) {
            MainMenuRouter.openSelected();
        }
    }
}
