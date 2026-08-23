package net.caffeinemc.mods.lithium.mixin.ai.pathing;

import net.minecraft.core.BlockPos;

/**
 * MODIFIED for porting: was a Mixin accessor interface. The {@code mixin.ai.pathing} option is disabled by default
 * upstream, so - exactly like upstream with the option off - no vanilla class implements this interface and the code in
 * {@code common.ai.pathing} that uses it is never reached (it is guarded by
 * {@code BlockStatePathingCache.class.isAssignableFrom(...)}).
 */
public interface PathfindingContextAccessor {

    BlockPos.MutableBlockPos getLastNodePos();
}
