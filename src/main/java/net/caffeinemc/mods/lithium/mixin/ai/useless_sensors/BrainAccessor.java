package net.caffeinemc.mods.lithium.mixin.ai.useless_sensors;

import java.util.Map;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;

/**
 * MODIFIED for porting: was a Mixin accessor/invoker interface; the vanilla class now implements it directly.
 */
public interface BrainAccessor<E extends LivingEntity> {

    Map<SensorType<? extends Sensor<? super E>>, Sensor<? super E>> getSensors();
}
