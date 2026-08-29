package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/**
 * Stops the view from lurching sideways when you take a hit.
 *
 * <p>The 1.16 client zeroed the player's {@code hurtTime} from a rendering callback - it changed game state
 * from inside drawing, and it took the red flash and the third-person hurt animation with it. This scales
 * the camera tilt where the tilt is read and nothing else: the flash, the animation and the hurt sound all
 * stay, because they belong to the entity rather than to the camera.</p>
 *
 * <p>The tilt is read from one extracted value, which the world view, the held item and a shader pack's own
 * hand pass all share - so one number covers all three, and the tilt cannot be removed from the world while
 * remaining on the hand.</p>
 */
public class NoHurtCam extends Module {

    private final NumberSetting tilt = this.register(new NumberSetting(
            "Tilt", "How much of the vanilla tilt survives. 0 removes it entirely, 1 is untouched.",
            0.0F, 0.0F, 1.0F, 0.05F));

    public NoHurtCam() {
        super(ModuleCategory.RENDER, "NoHurtCam", "Removes the camera tilt when you take damage");
    }

    /** What to multiply the game's damage-tilt strength by. */
    public float getTilt() {
        return this.tilt.get();
    }
}
