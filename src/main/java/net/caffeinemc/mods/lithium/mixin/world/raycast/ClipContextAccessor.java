package net.caffeinemc.mods.lithium.mixin.world.raycast;

import net.minecraft.world.level.ClipContext;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface ClipContextAccessor {

    ClipContext.Fluid getFluidHandling();
}
