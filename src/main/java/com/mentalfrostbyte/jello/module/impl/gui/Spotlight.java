package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.gui.jello.JelloSpotlightScreen;
import net.minecraft.client.gui.screens.Screen;

/** Jello's quick switch: a search bar over the game where a module's name and Enter switch it on or off. */
public class Spotlight extends ScreenLauncher {

    public Spotlight() {
        super("Spotlight", "Jello: a search bar; type a module's name and press Enter to switch it");
    }

    @Override
    protected Screen create() {
        return new JelloSpotlightScreen();
    }
}
