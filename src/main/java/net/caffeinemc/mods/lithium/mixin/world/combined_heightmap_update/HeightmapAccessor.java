package net.caffeinemc.mods.lithium.mixin.world.combined_heightmap_update;

import java.util.function.Predicate;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MODIFIED for porting: was a Mixin accessor/invoker interface; the vanilla class now implements it directly.
 */
public interface HeightmapAccessor {
    void callSet(int x, int z, int height);

    Predicate<BlockState> getBlockPredicate();
}
