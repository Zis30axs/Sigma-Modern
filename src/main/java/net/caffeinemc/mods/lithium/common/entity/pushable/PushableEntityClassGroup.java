package net.caffeinemc.mods.lithium.common.entity.pushable;

import net.caffeinemc.mods.lithium.common.entity.EntityClassGroup;
import net.caffeinemc.mods.lithium.common.reflection.ReflectionUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;

import java.util.function.Supplier;

public class PushableEntityClassGroup {

    /**
     * Contains Entity Classes that use {@link LivingEntity#isPushable()} to determine their pushability state
     * and use {@link LivingEntity#onClimbable()} to determine their climbing state and are never spectators (no players).
     * <p>
     * LivingEntity, but not Players and not Subclasses with different pushability calculations
     */
    public static final EntityClassGroup CACHABLE_UNPUSHABILITY;

    /**
     * Entities that might be pushable or are always pushable.
     * <p>
     * Pushable subclasses of ArmorStandEntity and BatEntity, Minecarts, Boats, LivingEntity. Not EnderDragonEntity
     */
    public static final EntityClassGroup MAYBE_PUSHABLE;

    static {
        String remapped_isClimbing = "onClimbable";
        String remapped_isPushable = "isPushable";
        CACHABLE_UNPUSHABILITY = new EntityClassGroup(
                (Class<?> entityClass, Supplier<EntityType<?>> entityType) -> {
                    if (LivingEntity.class.isAssignableFrom(entityClass) && !Player.class.isAssignableFrom(entityClass)) {
                        if (!ReflectionUtil.hasMethodOverride(entityClass, LivingEntity.class, true, remapped_isClimbing)) {
                            if (Creaking.class.isAssignableFrom(entityClass)) {
                                return !ReflectionUtil.hasMethodOverride(entityClass, Creaking.class, true, remapped_isPushable);
                            } else if (Warden.class.isAssignableFrom(entityClass)) {
                                return !ReflectionUtil.hasMethodOverride(entityClass, Warden.class, true, remapped_isPushable);
                            }
                            return !ReflectionUtil.hasMethodOverride(entityClass, LivingEntity.class, true, remapped_isPushable);
                        }
                    }
                    return false;
                });
        MAYBE_PUSHABLE = new EntityClassGroup(
                (Class<?> entityClass, Supplier<EntityType<?>> entityType) -> {
                    if (ReflectionUtil.hasMethodOverride(entityClass, Entity.class, true, remapped_isPushable)) {
                        if (EnderDragon.class.isAssignableFrom(entityClass)) {
                            return false;
                        }
                        if (ArmorStand.class.isAssignableFrom(entityClass)) {
                            return ReflectionUtil.hasMethodOverride(entityClass, ArmorStand.class, true, remapped_isPushable);
                        }
                        if (Bat.class.isAssignableFrom(entityClass)) {
                            return ReflectionUtil.hasMethodOverride(entityClass, Bat.class, true, remapped_isPushable);
                        }
                        return true;
                    }
                    //noinspection RedundantIfStatement
                    if (Player.class.isAssignableFrom(entityClass)) {
                        return true;
                    }
                    return false;
                });
    }
}
