package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;

/**
 * The movement keys and the two mouse buttons as a block of keys that light while they are held and send a ripple
 * out when they are let go. Only Jello draws it ({@code gui.legacy.hud.JelloWidgets}); SigmaModern has its own WASD
 * display built into its HUD.
 */
public class KeyStrokes extends Module {

    public KeyStrokes() {
        super(ModuleCategory.INTERFACE, "KeyStrokes", "Jello: shows the movement keys and mouse buttons you are pressing");
    }
}
