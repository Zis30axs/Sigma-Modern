package net.caffeinemc.mods.lithium.common.block;

import net.minecraft.world.level.block.state.BlockState;

public interface BlockCountingSection {
    boolean lithium$mayContainAny(TrackedBlockStatePredicate trackedBlockStatePredicate);

    short lithium$getCount(int predicateIndex);

    default short lithium$getCount(TrackedBlockStatePredicate trackedBlockStatePredicate) {
        return this.lithium$getCount(trackedBlockStatePredicate.getIndex());
    }

    void lithium$trackBlockStateChange(BlockState newState, BlockState oldState);
}
