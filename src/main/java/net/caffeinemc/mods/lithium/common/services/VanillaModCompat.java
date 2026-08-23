package net.caffeinemc.mods.lithium.common.services;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MODIFIED for porting: upstream had two implementations of {@link PlatformModCompat}. Both answer one question: does a
 * <em>third-party inventory API</em> (Fabric's {@code fabric-transfer-api-v1} {@code ItemStorage}, NeoForge's
 * {@code Capabilities.ItemHandler}) expose an inventory at the block the hopper is pointing at? Lithium needs that
 * because its hopper optimization caches the vanilla {@code Container} it found, and it must not use that cache when a
 * non-vanilla inventory could be present instead. On Fabric the answer is additionally guarded by
 * {@code isModLoaded("fabric-transfer-api-v1")}, i.e. it is {@code false} whenever that API is absent.
 * <p>
 * This project has no such API at all: hoppers can only ever see vanilla {@code Container}s. The honest answer here is
 * therefore always {@code false}, which is exactly the value upstream produces on a Fabric installation without the
 * transfer API.
 */
public class VanillaModCompat implements PlatformModCompat {
    @Override
    public boolean canHopperInteractWithApiBlockInventory(HopperBlockEntity hopperBlockEntity, BlockState hopperState, boolean extracting) {
        return false;
    }
}
