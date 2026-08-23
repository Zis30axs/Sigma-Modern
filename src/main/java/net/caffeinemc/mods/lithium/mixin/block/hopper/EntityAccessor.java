package net.caffeinemc.mods.lithium.mixin.block.hopper;

import net.minecraft.world.level.entity.EntityInLevelCallback;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface EntityAccessor {

    EntityInLevelCallback getChangeListener();
}
