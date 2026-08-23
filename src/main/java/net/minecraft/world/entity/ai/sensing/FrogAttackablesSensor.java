package net.minecraft.world.entity.ai.sensing;

import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.frog.Frog;

public class FrogAttackablesSensor extends NearestVisibleLivingEntitySensor {
    public static final float TARGET_DETECTION_DISTANCE = 10.0F;

    @Override
    protected boolean isMatchingEntity(final ServerLevel level, final LivingEntity body, final LivingEntity mob) {
        // MODIFIED for porting: lithium minimal_nonvanilla.ai.sensor.frog_attackables FrogAttackablesSensorMixin checks the
        // cheap entity-type test before the much more expensive attackability test.
        return Frog.canEat(mob) && Sensor.isEntityAttackable(level, body, mob) && !this.isUnreachableAttackTarget(body, mob)
            ? mob.closerThan(body, 10.0)
            : false;
    }

    private boolean isUnreachableAttackTarget(final LivingEntity body, final LivingEntity mob) {
        List<UUID> unreachableAttackTargets = body.getBrain().getMemory(MemoryModuleType.UNREACHABLE_TONGUE_TARGETS).orElseGet(ArrayList::new);
        return unreachableAttackTargets.contains(mob.getUUID());
    }

    @Override
    protected MemoryModuleType<LivingEntity> getMemoryToSet() {
        return MemoryModuleType.NEAREST_ATTACKABLE;
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return Sets.union(super.requires(), Set.of(MemoryModuleType.UNREACHABLE_TONGUE_TARGETS));
    }
}