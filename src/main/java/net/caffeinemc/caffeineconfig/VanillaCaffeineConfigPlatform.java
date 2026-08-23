package net.caffeinemc.caffeineconfig;

/**
 * MODIFIED for porting: replaces {@code CaffeineConfigFabric} / {@code CaffeineConfigNeoForge}, which walked the loader's mod
 * list looking for option overrides declared in mod metadata. There is no mod loader and therefore no other mod metadata in
 * this project, so no overrides can exist and {@link #applyModOverrides(CaffeineConfig, String)} has nothing to do. User
 * overrides from the properties file are unaffected - those are read by {@link CaffeineConfig} itself.
 */
public final class VanillaCaffeineConfigPlatform implements CaffeineConfigPlatform {
    @Override
    public void applyModOverrides(final CaffeineConfig config, final String jsonKey) {
    }
}
