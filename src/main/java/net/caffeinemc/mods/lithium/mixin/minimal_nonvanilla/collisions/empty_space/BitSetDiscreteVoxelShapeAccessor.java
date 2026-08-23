package net.caffeinemc.mods.lithium.mixin.minimal_nonvanilla.collisions.empty_space;

import java.util.BitSet;

/**
 * MODIFIED for porting: was a Mixin accessor/invoker interface; the vanilla class now implements it directly.
 */
public interface BitSetDiscreteVoxelShapeAccessor {

    BitSet getStorage();
}
