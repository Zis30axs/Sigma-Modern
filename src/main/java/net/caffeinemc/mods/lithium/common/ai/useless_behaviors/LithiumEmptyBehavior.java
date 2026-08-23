package net.caffeinemc.mods.lithium.common.ai.useless_behaviors;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.Set;

/**
 * Dummy behavior to replace useless behaviors with.
 * These will not be added to the brain of the entity.
 */
public class LithiumEmptyBehavior<E extends LivingEntity> implements BehaviorControl<E> {
    public static LithiumEmptyBehavior EMPTY_BEHAVIOR_SENTINEL = new LithiumEmptyBehavior();
    @Override
    public Behavior.Status getStatus() {
        return Behavior.Status.STOPPED;
    }

    @Override
    public Set<MemoryModuleType<?>> getRequiredMemories() {
        return Set.of();
    }

    @Override
    public boolean tryStart(ServerLevel serverLevel, E livingEntity, long l) {
        return false;
    }

    @Override
    public void tickOrStop(ServerLevel serverLevel, E livingEntity, long l) {
    }

    @Override
    public void doStop(ServerLevel serverLevel, E livingEntity, long l) {
    }

    @Override
    public String debugString() {
        return "Lithium Empty Behavior";
    }
}
