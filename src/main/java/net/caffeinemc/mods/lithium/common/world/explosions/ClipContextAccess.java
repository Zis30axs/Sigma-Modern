package net.caffeinemc.mods.lithium.common.world.explosions;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public interface ClipContextAccess {

    void lithium$setFrom(Vec3 from);

    CollisionContext lithium$getCollisionContext();
}
