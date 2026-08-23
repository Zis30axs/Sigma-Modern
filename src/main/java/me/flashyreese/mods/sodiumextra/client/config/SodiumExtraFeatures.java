package me.flashyreese.mods.sodiumextra.client.config;

import me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod;
import net.caffeinemc.caffeineconfig.Option;

/**
 * MODIFIED for porting: replaces {@code SodiumExtraMixinConfigPlugin} / {@code AbstractCaffeineConfigMixinPlugin}.
 * <p>
 * Upstream, {@code sodium-extra.properties} decides whether a mixin package is <em>applied at all</em>: the mixin config
 * plugin resolves the effective option for every mixin class and refuses to apply the ones whose option is disabled. This
 * project has no mixins, so the same decision has to be made at run time instead: every feature that used to be a mixin
 * package is guarded by the constant here that corresponds to its option, and the constant is resolved with the very same
 * {@link net.caffeinemc.caffeineconfig.CaffeineConfig#getEffectiveOptionForMixin(String)} call the plugin used, passing the
 * former mixin class name. The properties file therefore keeps working exactly as documented.
 * <p>
 * The values are resolved once, when this class is initialised, because the config is read once at startup and never
 * changes afterwards - this both matches the "decided before the code is loaded" semantics of the mixin plugin and keeps the
 * checks free in hot render paths.
 */
public final class SodiumExtraFeatures {
    public static final boolean ADAPTIVE_SYNC = enabled("adaptive_sync.MixinGlSurface");

    public static final boolean ANIMATION = enabled("animation.MixinTextureAtlas");

    public static final boolean BIOME_COLORS = enabled("biome_colors.MixinBiomeColors");

    public static final boolean CLOUD = enabled("cloud.MixinLevelRenderer");

    public static final boolean FOG = enabled("fog.MixinFogRenderer");

    public static final boolean FPS = enabled("fps.MixinGameRenderer");

    public static final boolean GUI = enabled("gui.MixinGui");

    public static final boolean INSTANT_SNEAK = enabled("instant_sneak.MixinCamera");

    public static final boolean LIGHT_UPDATES = enabled("light_updates.MixinLevelLightEngine");

    public static final boolean PANINI_PROJECTION = enabled("panini_projection.MixinGameRenderer");

    public static final boolean PARTICLE = enabled("particle.MixinParticleEngine");

    public static final boolean PREVENT_SHADERS = enabled("prevent_shaders.MixinGameRenderer");

    public static final boolean REDUCE_RESOLUTION_ON_MAC = enabled("reduce_resolution_on_mac.MixinWindow");

    public static final boolean RENDER_BLOCK_ENTITY = enabled("render.block.entity.MixinBeaconRenderer");

    public static final boolean RENDER_ENTITY = enabled("render.entity.MixinLivingEntityRenderer");

    public static final boolean SKY = enabled("sky.MixinSkyRenderer");

    public static final boolean SKY_COLORS = enabled("sky_colors.MixinAtmosphericFogEnvironment");

    public static final boolean STEADY_DEBUG_HUD = enabled("steady_debug_hud.MixinDebugScreenOverlay");

    public static final boolean TOASTS = enabled("toasts.MixinToastManager");

    private SodiumExtraFeatures() {
    }

    /**
     * Resolves the effective option for a former mixin class name, exactly as
     * {@code AbstractCaffeineConfigMixinPlugin#shouldApplyMixin} did.
     */
    private static boolean enabled(final String formerMixinClassName) {
        Option option = SodiumExtraClientMod.mixinConfig().getEffectiveOptionForMixin(formerMixinClassName);

        if (option == null) {
            // The plugin threw here; every name above is covered by an option, so this can only be a programming error.
            throw new IllegalStateException("No options matched '" + formerMixinClassName + "'!");
        }

        return option.isEnabled();
    }
}
