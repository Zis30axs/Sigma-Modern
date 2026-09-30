package com.mentalfrostbyte.jello.gui.legacy.hud;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.client.EventModuleToggle;
import com.mentalfrostbyte.jello.gui.ClientMode;
import com.mentalfrostbyte.jello.gui.legacy.LegacySounds;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * The sound the old client made as a module was switched: Jello's activate / deactivate cues, Classic's stone button
 * click. Whether it does is the ArrayList module's {@code Sound} setting, as the old ActiveMods had it, and it is
 * asked whether or not the list itself is on. SigmaModern has its own way of showing a toggle and stays quiet.
 *
 * <p>Registered on the event bus for the whole run: a toggle is announced by {@link EventModuleToggle}, so the module
 * layer never reaches out to a sound.</p>
 */
public final class LegacyToggleSound {

    @EventTarget
    public void onToggle(final EventModuleToggle event) {
        Client client = Client.getInstance();
        ClientMode mode = client.getClientModeManager().get();
        if (mode != ClientMode.JELLO && mode != ClientMode.CLASSIC) {
            return;
        }

        if (!client.getModuleManager().get(ModuleArrayList.class).playsSound()) {
            return;
        }

        if (mode == ClientMode.JELLO) {
            LegacySounds.play(event.isEnabled() ? LegacySounds.Cue.ACTIVATE : LegacySounds.Cue.DEACTIVATE);
        } else {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.STONE_BUTTON_CLICK_ON, 0.6F));
        }
    }
}
