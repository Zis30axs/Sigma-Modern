package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;

/**
 * Your position in small light type down the left side, faint while you stand still and brightening with a little
 * pop when you move. A module with nothing to configure: switching it on is the whole feature.
 *
 * <p>Only Jello draws it ({@code gui.legacy.hud.JelloWidgets}). Which interface draws a module is decided where the
 * drawing happens, so it is registered in every one and simply does nothing where there is no drawing for it.</p>
 */
public class Coords extends Module {

    public Coords() {
        super(ModuleCategory.INTERFACE, "Coords", "Jello: your coordinates on the left, faint until you move");
    }
}
