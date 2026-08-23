package net.caffeinemc.mods.lithium.mixin.util.entity_movement_tracking;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly. Upstream declares
 * the same accessor twice (see {@code mixin.util.accessors}) so that both mixin config options can be toggled
 * independently; both declarations are kept.
 */
public interface PersistentEntitySectionManagerAccessor<T extends EntityAccess> {
    EntitySectionStorage<T> getCache();
}
