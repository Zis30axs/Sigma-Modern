package net.caffeinemc.mods.lithium.mixin.util.entity_movement_tracking;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly. Upstream declares
 * the same accessor twice (see {@code mixin.util.accessors}); both declarations are kept.
 */
public interface ServerLevelAccessor {
    PersistentEntitySectionManager<Entity> getEntityManager();
}
