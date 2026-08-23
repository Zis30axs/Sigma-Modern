package me.flashyreese.mods.sodiumextra;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod;
import me.flashyreese.mods.sodiumextra.client.config.SodiumExtraConfig;
import me.flashyreese.mods.sodiumextra.client.recovery.WaylandFullscreenResolutionRecovery;
import net.caffeinemc.mods.sodium.client.SodiumBootstrap;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;

/**
 * MODIFIED for porting: replaces sodium-extra's loader entry points {@code SodiumExtraFabricPreLaunch} (Fabric
 * {@code PreLaunchEntrypoint}), {@code SodiumExtraFabricClientModInitializer} ({@code ClientModInitializer}) and the
 * {@code sodium:config_api_user} entry point declared in {@code fabric.mod.json}. There is no mod loader in this project, so
 * {@link net.minecraft.client.main.Main} calls {@link #bootstrap(Path)} once, after
 * {@link SodiumBootstrap#bootstrap(Path)} and before {@link SodiumBootstrap#finishConfigRegistration()} - which is the same
 * ordering the loader guarantees (sodium-extra depends on sodium, and all config entry points are collected before the config
 * screens are built).
 * <p>
 * Differences to the loader versions, all of them a direct consequence of there being no loader:
 * <ul>
 *   <li>the mod version is a constant instead of being read from the mod metadata;</li>
 *   <li>the config entry point is registered by this class instead of being discovered from mod metadata;</li>
 *   <li>the debug screen entries are registered straight through
 *       {@link DebugScreenEntries#register(Identifier, net.minecraft.client.gui.components.debug.DebugScreenEntry)} instead of
 *       through Fabric's entry point / NeoForge's {@code RegisterDebugEntriesEvent}. The Fabric version's profile handling is
 *       kept, since it is the one that matches this code path.</li>
 * </ul>
 */
public final class SodiumExtraBootstrap {
    /**
     * The version of sodium-extra that was ported. Upstream reads this from the mod metadata, which does not exist here.
     */
    public static final String MOD_VERSION = "0.9.3+mc26.2";

    private static boolean initialized;

    private SodiumExtraBootstrap() {
    }

    /**
     * Runs both of sodium-extra's loader entry points, in their original order.
     */
    public static void bootstrap(final Path gameDirectory) {
        if (initialized) {
            return;
        }

        initialized = true;
        // Was SodiumExtraFabricPreLaunch#onPreLaunch
        WaylandFullscreenResolutionRecovery.recoverIfNeeded(gameDirectory, gameDirectory.resolve("config"));
        // Was the sodium:config_api_user entry point in fabric.mod.json
        SodiumBootstrap.registerModMetadata("sodium-extra", "Sodium Extra", MOD_VERSION);
        ConfigManager.registerConfigEntryPoint(SodiumExtraConfig::new, "sodium-extra");
        // Was SodiumExtraFabricClientModInitializer#initFabric
        SodiumExtraClientMod.registerAll(DebugScreenEntries::register);

        Identifier lightUpdatesWarning = Identifier.fromNamespaceAndPath("sodium-extra", "sodium-extra.option.light_updates_warning");

        Map<Identifier, DebugScreenEntryStatus> defaultProfile = new HashMap<>(DebugScreenEntries.PROFILES.get(DebugScreenProfile.DEFAULT));
        Map<Identifier, DebugScreenEntryStatus> performanceProfile = new HashMap<>(DebugScreenEntries.PROFILES.get(DebugScreenProfile.PERFORMANCE));

        defaultProfile.put(lightUpdatesWarning, DebugScreenEntryStatus.IN_OVERLAY);
        performanceProfile.put(lightUpdatesWarning, DebugScreenEntryStatus.IN_OVERLAY);

        Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> modifiedProfiles = new HashMap<>(DebugScreenEntries.PROFILES);
        modifiedProfiles.put(DebugScreenProfile.DEFAULT, Map.copyOf(defaultProfile));
        modifiedProfiles.put(DebugScreenProfile.PERFORMANCE, Map.copyOf(performanceProfile));

        DebugScreenEntries.PROFILES = Collections.unmodifiableMap(modifiedProfiles);
    }
}
