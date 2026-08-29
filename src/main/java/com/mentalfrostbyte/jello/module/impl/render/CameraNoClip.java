package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;

/**
 * Keeps the third-person camera where it belongs instead of pulling it in to the nearest wall.
 *
 * <p>The 1.16 version of this module was an empty class - it existed only so that a patch elsewhere could
 * ask whether it was switched on. That is still the right shape, and this one is honest about it: the module
 * carries no settings and no behaviour, and the camera code asks it a question.</p>
 *
 * <p>Two things follow from the camera no longer stopping at walls, and neither is a bug: with the camera
 * outside the room the sound listener is out there too, so muffled things sound closer than they are; and a
 * camera that ends up inside a solid block sees through the world, because the game's own way out of that
 * situation is reserved for spectators.</p>
 */
public class CameraNoClip extends Module {

    public CameraNoClip() {
        super(ModuleCategory.RENDER, "CameraNoClip", "Lets the third-person camera pass through blocks");
    }
}
