package net.caffeinemc.mods.sodium.client;

import net.caffeinemc.mods.sodium.client.compatibility.checks.PreLaunchChecks;
import net.caffeinemc.mods.sodium.client.compatibility.environment.probe.GraphicsAdapterProbe;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder;
import net.caffeinemc.mods.sodium.client.services.FRAPIProvider;

/**
 * MODIFIED for porting: replaces the loader entry points {@code SodiumPreLaunch} (Fabric {@code PreLaunchEntrypoint} /
 * NeoForge equivalent) and {@code SodiumFabricMod} ({@code ClientModInitializer}). There is no mod loader in this project, so
 * {@link net.minecraft.client.main.Main} calls {@link #bootstrap()} once, before the {@link net.minecraft.client.Minecraft}
 * instance is created - which is the same ordering the loader entry points guarantee.
 * <p>
 * Differences to the loader versions, all of them a direct consequence of there being no loader:
 * <ul>
 *   <li>the mod version is a constant instead of being read from the mod metadata;</li>
 *   <li>{@code ConfigManager.setModInfoFunction} answers from {@link #registerModMetadata(String, String, String)}, which
 *       the other ported mods call for themselves, instead of from the loader's mod list;</li>
 *   <li>config entry points are not discovered by scanning mod metadata for {@code sodium:config_api_user}; every ported mod
 *       that declares that entry point registers it from its own bootstrap, and sodium's own {@link SodiumConfigBuilder} is
 *       registered last by {@link #finishConfigRegistration()} - the same order {@code ConfigLoaderFabric} used;</li>
 *   <li>the {@code frex_flawless_frames} entry points are not looked up, so nothing takes control of
 *       {@link net.caffeinemc.mods.sodium.client.util.FlawlessFrames} (it stays inactive, exactly as on a Fabric install
 *       without FREX);</li>
 *   <li>{@link FRAPIProvider} is the no-op implementation, so its {@code register()} does nothing.</li>
 * </ul>
 */
public final class SodiumBootstrap {
    /**
     * The version of sodium that was ported. Upstream reads this from the mod metadata, which does not exist here.
     */
    public static final String MOD_VERSION = "0.9.1+mc26.2";

    private static final java.util.Map<String, ConfigManager.ModMetadata> MOD_METADATA = new java.util.HashMap<>();

    private static boolean initialized;

    private SodiumBootstrap() {
    }

    /**
     * MODIFIED for porting: stands in for the loader's mod list. Every ported mod that shows up in sodium's config screen
     * registers its name and version here, before {@link #finishConfigRegistration()} runs.
     */
    public static void registerModMetadata(final String modId, final String modName, final String modVersion) {
        MOD_METADATA.put(modId, new ConfigManager.ModMetadata(modName, modVersion));
    }

    /**
     * Runs both of sodium's loader entry points, in their original order.
     */
    public static void bootstrap(final java.nio.file.Path gameDirectory) {
        if (initialized) {
            return;
        }

        initialized = true;
        // The loader knew the game directory before the client existed; sodium's pre-launch code needs it too.
        net.caffeinemc.mods.sodium.client.services.vanilla.VanillaRuntimeInformation.setGameDirectory(gameDirectory);
        // Was SodiumPreLaunch#onPreLaunch
        PreLaunchChecks.checkEnvironment();
        GraphicsAdapterProbe.findAdapters();
        Workarounds.init();
        // Was SodiumFabricMod#onInitializeClient
        SodiumClientMod.onInitialization(MOD_VERSION);
        registerModMetadata("sodium", "Sodium", MOD_VERSION);
        // Was ConfigLoaderFabric#collectConfigEntryPoints, first half
        ConfigManager.setModInfoFunction(SodiumBootstrap::getModMetadata);
        FRAPIProvider.getInstance().register();
    }

    /**
     * MODIFIED for porting: the second half of {@code ConfigLoaderFabric#collectConfigEntryPoints} plus
     * {@code ConfigManager.registerConfigsEarly()}. It has to run after every other ported mod has registered its config
     * entry point, so {@link net.minecraft.client.main.Main} calls it once all the mods have been bootstrapped - the loader
     * achieved the same by scanning all mod metadata before building the configs.
     */
    public static void finishConfigRegistration() {
        ConfigManager.registerConfigEntryPoint(SodiumConfigBuilder::new, "sodium");
        ConfigManager.registerConfigsEarly();
    }

    /**
     * MODIFIED for porting: was {@code ConfigLoaderFabric#getModMetadata}, which asked the loader for the mod container.
     */
    private static ConfigManager.ModMetadata getModMetadata(final String modId) {
        ConfigManager.ModMetadata metadata = MOD_METADATA.get(modId);

        if (metadata == null) {
            throw new NullPointerException("No metadata available for mod '" + modId + "' - this build has no mod loader");
        }

        return metadata;
    }
}
