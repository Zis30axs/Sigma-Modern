package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.event.EventState;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * A module that is a way to a screen: switching it on opens the screen and switches it straight off again.
 *
 * <p>The old Jello client bound its own screens (the Spotlight, Maps, the games) to keys next to its modules. Here a
 * screen is reached through a module so that it needs nothing of its own: it is bound in the Keybind Manager or the
 * ClickGUI like any other, switched on from the TabGUI, and its state is saved with the rest of the config.</p>
 *
 * <p>The screen opens on the next client tick rather than from inside {@code onEnable}, so the switch that turned this
 * module on has finished - listeners installed, the toggle announced - before the screen takes over.</p>
 */
public abstract class ScreenLauncher extends Module {

    protected ScreenLauncher(final String name, final String description) {
        super(ModuleCategory.INTERFACE, name, description);
    }

    /** The screen to open. */
    protected abstract Screen create();

    @EventTarget
    public void onTick(final EventTick event) {
        if (event.getState() != EventState.PRE) {
            return;
        }

        this.setEnabled(false);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            mc.gui.setScreen(this.create());
        }
    }
}
