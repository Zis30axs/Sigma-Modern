package net.caffeinemc.mods.lithium.mixin.util.accessors;

import net.minecraft.world.level.chunk.Configuration;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface StrategyAccessor {
    Configuration lithium$getConfigurationForPaletteSize(int i);
}
