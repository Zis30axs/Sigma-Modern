package net.caffeinemc.mods.lithium.common.tracking.block;

import net.caffeinemc.mods.lithium.common.block.BlockListeningSection;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

public interface BlockChangeTracker {

    /**
     * Handles block changes from the subscribed section.
     * <p>
     * Staying subscribed to a section can be computationally expensive, since bursts of block changes,
     * e.g. redstone wire flicker, can lead to thousands of block changes. Use cautiously!
     *
     * @return Whether this tracker should stay subscribed.
     */
    boolean setChanged(BlockListeningSection section, int localX, int localY, int localZ, BlockState oldState, BlockState newState);

    void onChunkSectionInvalidated(SectionPos sectionPos);
}
