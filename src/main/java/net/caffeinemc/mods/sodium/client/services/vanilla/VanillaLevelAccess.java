package net.caffeinemc.mods.sodium.client.services.vanilla;

import net.caffeinemc.mods.sodium.client.services.PlatformLevelAccess;
import net.caffeinemc.mods.sodium.client.world.SodiumAuxiliaryLightManager;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * MODIFIED for porting: replaces the loader specific {@code FabricLevelAccess} / {@code NeoForgeLevelAccess}.
 * Vanilla block entities have no extra render data ({@code BlockEntity#getRenderData} is a Fabric API addition) and
 * vanilla has no auxiliary light managers, so both lookups return null. {@code PlatformBlockAccess#platformHasBlockData}
 * returns false accordingly, so the block entity data is never requested in the first place.
 */
public class VanillaLevelAccess implements PlatformLevelAccess {
    @Override
    public @Nullable Object getBlockEntityData(final BlockEntity blockEntity) {
        return null;
    }

    @Override
    public @Nullable SodiumAuxiliaryLightManager getLightManager(final LevelChunk chunk, final SectionPos pos) {
        return null;
    }
}
