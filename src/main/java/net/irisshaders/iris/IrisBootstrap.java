package net.irisshaders.iris;

import net.caffeinemc.mods.sodium.client.SodiumBootstrap;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.irisshaders.iris.compat.sodium.config.IrisConfig;
import net.irisshaders.iris.platform.VanillaIrisPlatformHelpers;

/**
 * MODIFIED for porting: replaces the {@code sodium:config_api_user} entry point declared in Iris's
 * {@code fabric.mod.json}. There is no mod loader in this project, so {@link net.minecraft.client.main.Main}
 * calls {@link #bootstrap()} once, after {@link SodiumBootstrap#bootstrap} and before
 * {@link SodiumBootstrap#finishConfigRegistration()} - the same ordering the loader guarantees.
 * <p>
 * Iris's actual client initialization ({@link Iris#onEarlyInitialize}) still runs inside Minecraft's
 * constructor, because it needs the options/key-binding registration environment.
 */
public final class IrisBootstrap {
    /**
     * The version of Iris that was ported. Upstream reads this from the mod metadata, which does not exist here.
     */
    public static final String MOD_VERSION = VanillaIrisPlatformHelpers.IRIS_VERSION;

    private static boolean initialized;

    private IrisBootstrap() {
    }

    public static void bootstrap() {
        if (initialized) {
            return;
        }

        initialized = true;
        // Was the sodium:config_api_user entry point in iris's fabric.mod.json
        SodiumBootstrap.registerModMetadata("iris", "Iris", MOD_VERSION);
        ConfigManager.registerConfigEntryPoint(IrisConfig::new, "iris");
    }
}
