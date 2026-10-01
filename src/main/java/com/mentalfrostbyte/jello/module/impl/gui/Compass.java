package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;

/**
 * The directions along the top of the screen, sliding past as you turn (the old "Fortnite style directions").
 * Only Jello draws it ({@code gui.legacy.hud.JelloWidgets}).
 */
public class Compass extends Module {

    public Compass() {
        super(ModuleCategory.INTERFACE, "Compass", "Jello: the directions along the top of the screen, sliding as you turn");
    }
}
