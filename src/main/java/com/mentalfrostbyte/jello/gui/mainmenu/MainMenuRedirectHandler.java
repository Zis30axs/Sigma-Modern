package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.gui.ClientMode;
import com.mentalfrostbyte.jello.util.game.MinecraftInstance;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Keeps the selected Sigma main menu after leaving a world or server.
 *
 * <p>Vanilla recreates {@link TitleScreen} from several disconnect paths. Replacing it on the next client
 * tick avoids scattering Sigma branches through those vanilla call sites while still making the persisted
 * presentation the stable title screen.</p>
 */
public final class MainMenuRedirectHandler implements MinecraftInstance {

    @EventTarget
    public void onTick(final EventTick event) {
        Client client = Client.getInstance();
        if (!event.isPre() || !client.isStarted() || client.getClientModeManager().get() == ClientMode.NO_ADDONS) {
            return;
        }

        if (mc.level == null && mc.gui.screen() instanceof TitleScreen) {
            MainMenuRouter.openSelected();
        }
    }
}
