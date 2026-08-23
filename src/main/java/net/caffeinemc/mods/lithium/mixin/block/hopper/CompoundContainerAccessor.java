package net.caffeinemc.mods.lithium.mixin.block.hopper;

import net.minecraft.world.Container;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface CompoundContainerAccessor {

    Container getFirst();

    Container getSecond();
}
