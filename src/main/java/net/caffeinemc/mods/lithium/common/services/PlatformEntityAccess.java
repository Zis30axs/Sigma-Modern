package net.caffeinemc.mods.lithium.common.services;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.function.Predicate;

public interface PlatformEntityAccess {
    // MODIFIED for porting: upstream resolved the platform implementation through a ServiceLoader
    // (net.caffeinemc.mods.lithium.common.services.Services). There is exactly one platform here, so the vanilla
    // implementation - which is what the Fabric module used - is referenced directly.
    PlatformEntityAccess INSTANCE = new VanillaEntityAccess();

    void addEnderDragonParts(Level level, Entity excludedEntity, AABB box, Predicate<? super Entity> entityFilter, ArrayList<Entity> entities);

}
