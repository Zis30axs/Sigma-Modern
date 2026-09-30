package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.gui.jello.JelloMapsScreen;
import net.minecraft.client.gui.screens.Screen;

/** Jello's map: what has been seen of this world as one picture, with the waypoints you have set on it. */
public class Maps extends ScreenLauncher {

    public Maps() {
        super("Maps", "Jello: a map of the parts of this world you have seen, with waypoints");
    }

    @Override
    protected Screen create() {
        return new JelloMapsScreen();
    }
}
