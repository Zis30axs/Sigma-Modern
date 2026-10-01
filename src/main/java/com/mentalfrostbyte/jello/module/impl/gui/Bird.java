package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.gui.jello.JelloBirdScreen;
import net.minecraft.client.gui.screens.Screen;

/** Jello's bird: flap with Space through the gaps in the pipes. */
public class Bird extends ScreenLauncher {

    public Bird() {
        super("Bird", "Jello: a flapping game; Space to flap");
    }

    @Override
    protected Screen create() {
        return new JelloBirdScreen();
    }
}
