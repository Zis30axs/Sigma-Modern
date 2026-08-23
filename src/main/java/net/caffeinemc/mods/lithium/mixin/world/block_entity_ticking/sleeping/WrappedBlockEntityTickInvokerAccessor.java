package net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping;

import net.minecraft.world.level.block.entity.TickingBlockEntity;

/**
 * MODIFIED for porting: was a Mixin accessor/invoker interface on
 * {@code LevelChunk$RebindableTickingBlockEntityWrapper}; the vanilla class now implements it directly.
 */
public interface WrappedBlockEntityTickInvokerAccessor {
    void callSetWrapped(TickingBlockEntity wrapped);

    TickingBlockEntity getWrapped();
}
