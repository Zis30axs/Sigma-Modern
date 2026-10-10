package com.mentalfrostbyte.jello.module.impl.movement;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;

/** The local player's jump cooldown is cleared at the start of LivingEntity.aiStep. */
public final class NoJumpDelay extends Module {

    public NoJumpDelay() {
        super(ModuleCategory.MOVEMENT, "NoJumpDelay", "Removes the delay between grounded jumps.");
    }
}
