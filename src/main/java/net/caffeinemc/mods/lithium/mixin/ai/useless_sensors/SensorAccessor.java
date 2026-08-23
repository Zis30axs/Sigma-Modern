package net.caffeinemc.mods.lithium.mixin.ai.useless_sensors;

/**
 * MODIFIED for porting: was a Mixin accessor/invoker interface; the vanilla class now implements it directly.
 */
public interface SensorAccessor {

    long getTimeToTick();

    int getSenseInterval();

    void setTimeToTick(long lastSenseTime);
}
