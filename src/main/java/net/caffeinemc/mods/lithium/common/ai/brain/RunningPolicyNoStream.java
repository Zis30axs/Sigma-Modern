package net.caffeinemc.mods.lithium.common.ai.brain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.ShufflingList;

public interface RunningPolicyNoStream {

    <E extends LivingEntity> void lithium$apply(ShufflingList<BehaviorControl<? super E>> behaviors, ServerLevel serverLevel, E livingEntity, long l);
}
