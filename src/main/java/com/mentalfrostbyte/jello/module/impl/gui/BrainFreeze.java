package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;

/** Snow falling over Jello's ClickGUI, behind its cards. Only Jello's ClickGUI draws it. */
public class BrainFreeze extends Module {

    public BrainFreeze() {
        super(ModuleCategory.INTERFACE, "BrainFreeze", "Jello: snow falling over the ClickGUI");
    }
}
