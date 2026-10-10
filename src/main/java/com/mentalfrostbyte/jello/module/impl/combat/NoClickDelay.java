package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;

/** Policy for the ordinary air-miss penalty in Minecraft.startAttack. */
public final class NoClickDelay extends Module {
    public NoClickDelay() {
        super(ModuleCategory.COMBAT, "NoClickDelay", "Removes the delay after missing a left-click attack.");
    }
}
