package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;

/**
 * A small map of the ground around you, turning so that the way you face is up. Only Jello draws it
 * ({@code gui.legacy.hud.JelloMiniMap}).
 */
public class MiniMap extends Module {

    public MiniMap() {
        super(ModuleCategory.INTERFACE, "MiniMap", "Jello: a map of the ground around you, turning as you do");
    }
}
