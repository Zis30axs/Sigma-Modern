package net.caffeinemc.mods.lithium.common.services;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public interface PlatformModCompat {
    // MODIFIED for porting: see PlatformEntityAccess - the ServiceLoader indirection is gone.
    PlatformModCompat INSTANCE = new VanillaModCompat();

    boolean canHopperInteractWithApiBlockInventory(HopperBlockEntity hopperBlockEntity, BlockState hopperState, boolean extracting);
}
