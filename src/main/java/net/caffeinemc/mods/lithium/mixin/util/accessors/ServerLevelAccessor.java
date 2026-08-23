package net.caffeinemc.mods.lithium.mixin.util.accessors;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface ServerLevelAccessor {
    PersistentEntitySectionManager<Entity> getEntityManager();
}
