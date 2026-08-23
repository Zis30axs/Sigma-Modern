package net.caffeinemc.mods.lithium.mixin.minimal_nonvanilla.spawning;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class implements it directly. Upstream declares the
 * same accessor once per mixin config option so the options can be toggled independently; all declarations are kept.
 */
public interface PersistentEntitySectionManagerAccessor<T extends EntityAccess> {
    EntitySectionStorage<T> getCache();
}
