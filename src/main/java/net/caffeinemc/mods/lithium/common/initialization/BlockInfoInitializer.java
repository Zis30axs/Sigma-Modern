package net.caffeinemc.mods.lithium.common.initialization;

import net.caffeinemc.mods.lithium.common.ai.pathing.BlockStatePathingCache;
import net.caffeinemc.mods.lithium.common.block.BlockStateFlagHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class BlockInfoInitializer {

    // Initialize the block info.
    // This is called before burn times are set in the minecraft server, but we don't care about the updated burn times.
    // But burn times are updated after block tags etc. are modified, so it is a good hook
    public static void initializeBlockInfo() {
        if (BlockStatePathingCache.class.isAssignableFrom(BlockState.class)) {
            // Initialize / Reinitialize the cached path node types.
            for (BlockState blockState : Block.BLOCK_STATE_REGISTRY) {
                ((BlockStatePathingCache) blockState).lithium$initializePathNodeTypeCache();
            }
        }

        if (BlockStateFlagHolder.class.isAssignableFrom(BlockState.class)) {
            // Initialize / Reinitialize the cached block flags.
            for (BlockState blockState : Block.BLOCK_STATE_REGISTRY) {
                ((BlockStateFlagHolder) blockState).lithium$initializeFlags();
            }
        }
    }
}
