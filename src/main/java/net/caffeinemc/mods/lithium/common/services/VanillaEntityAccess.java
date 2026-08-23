package net.caffeinemc.mods.lithium.common.services;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.function.Predicate;

/**
 * MODIFIED for porting: this is upstream's {@code net.caffeinemc.mods.lithium.fabric.FabricEntityAccess}, which only
 * uses vanilla API ({@link Level#dragonParts()}); the NeoForge variant additionally covered NeoForge's own
 * {@code PartEntity}, which does not exist here.
 */
public class VanillaEntityAccess implements PlatformEntityAccess {
    @Override
    public void addEnderDragonParts(Level level, Entity excludedEntity, AABB box, Predicate<? super Entity> entityFilter, ArrayList<Entity> entities) {
        for (EnderDragonPart enderDragonPart : level.dragonParts()) {
            if (enderDragonPart != excludedEntity
                    && enderDragonPart.parentMob != excludedEntity
                    && entityFilter.test(enderDragonPart) &&
                    box.intersects(enderDragonPart.getBoundingBox())
            ) {
                entities.add(enderDragonPart);
            }
        }
    }
}
