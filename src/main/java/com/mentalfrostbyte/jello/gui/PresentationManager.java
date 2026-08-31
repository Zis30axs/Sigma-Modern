package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.gui.classic.ClassicModuleScreen;
import com.mentalfrostbyte.jello.gui.jello.JelloModuleScreen;
import com.mentalfrostbyte.jello.gui.noaddons.NoAddonsScreen;
import com.mentalfrostbyte.jello.module.ModuleManager;
import net.minecraft.client.gui.screens.Screen;

/**
 * Selects the ClickGUI screen for the active {@link ClientMode}.
 *
 * <p>The presentation layer may have its own layout, navigation and widgets, but it always reads the same
 * {@link ModuleManager} and the modules' own {@link com.mentalfrostbyte.jello.setting.SettingHolder}
 * views. No presentation mode creates a second module registry.</p>
 */
public final class PresentationManager {

    private final ClientModeManager clientModeManager;

    public PresentationManager(final ClientModeManager clientModeManager) {
        this.clientModeManager = clientModeManager;
    }

    public Screen createClickGui(final ModuleManager modules) {
        return switch (this.clientModeManager.get()) {
            case JELLO -> new JelloModuleScreen(modules);
            case CLASSIC -> new ClassicModuleScreen(modules);
            case NO_ADDONS -> new NoAddonsScreen();
        };
    }
}
