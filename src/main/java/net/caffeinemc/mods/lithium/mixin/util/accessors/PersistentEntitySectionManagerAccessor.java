package net.caffeinemc.mods.lithium.mixin.util.accessors;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface PersistentEntitySectionManagerAccessor<T extends EntityAccess> {
    EntitySectionStorage<T> getCache();
}
