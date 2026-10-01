package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.gui.jello.JelloSnakeScreen;
import net.minecraft.client.gui.screens.Screen;

/** Jello's snake, on its own card over the game; Escape leaves. */
public class Snake extends ScreenLauncher {

    public Snake() {
        super("Snake", "Jello: a game of snake; steer with the movement keys");
    }

    @Override
    protected Screen create() {
        return new JelloSnakeScreen();
    }
}
