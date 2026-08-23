package net.caffeinemc.mods.sodium.mixin.core.render.world;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface EntityRendererAccessor {
    AABB sodium$getBoundingBoxForCulling(Entity entity);
}
