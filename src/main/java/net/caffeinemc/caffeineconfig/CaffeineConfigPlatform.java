package net.caffeinemc.caffeineconfig;

public interface CaffeineConfigPlatform {
    void applyModOverrides(CaffeineConfig config, String jsonKey);
}
