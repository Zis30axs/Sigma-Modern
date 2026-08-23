package me.flashyreese.mods.sodiumextra.common.util;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod;
import net.minecraft.resources.Identifier;

/**
 * MODIFIED for porting: holds the {@code @Unique} members of sodium-extra's {@code animation.MixinTextureAtlas}, namely its
 * {@code animatedSprites} table and {@code shouldAnimate(Identifier)}. The mixin declared the table as a per-atlas instance
 * field even though it is the same immutable table for every atlas and every entry re-reads the live option through its
 * supplier, so it is a static table here; the lookup itself is unchanged.
 */
public final class AnimatedSpriteFilter {
    private static final Map<Supplier<Boolean>, List<Identifier>> ANIMATED_SPRITES = Map.of(
            () -> SodiumExtraClientMod.options().animationSettings.water, List.of(
                    Identifier.fromNamespaceAndPath("minecraft", "block/water_still"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/water_flow")
            ),
            () -> SodiumExtraClientMod.options().animationSettings.lava, List.of(
                    Identifier.fromNamespaceAndPath("minecraft", "block/lava_still"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/lava_flow")
            ),
            () -> SodiumExtraClientMod.options().animationSettings.portal, List.of(
                    Identifier.fromNamespaceAndPath("minecraft", "block/nether_portal")
            ),
            () -> SodiumExtraClientMod.options().animationSettings.fire, List.of(
                    Identifier.fromNamespaceAndPath("minecraft", "block/fire_0"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/fire_1"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/soul_fire_0"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/soul_fire_1"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/campfire_fire"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/campfire_log_lit"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/soul_campfire_fire"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/soul_campfire_log_lit")
            ),
            () -> SodiumExtraClientMod.options().animationSettings.blockAnimations, List.of(
                    Identifier.fromNamespaceAndPath("minecraft", "block/magma"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/lantern"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/sea_lantern"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/soul_lantern"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/kelp"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/kelp_plant"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/seagrass"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/tall_seagrass_top"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/tall_seagrass_bottom"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/warped_stem"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/crimson_stem"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/blast_furnace_front_on"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/smoker_front_on"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/stonecutter_saw"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/prismarine"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/respawn_anchor_top"),
                    Identifier.fromNamespaceAndPath("minecraft", "entity/conduit/wind"),
                    Identifier.fromNamespaceAndPath("minecraft", "entity/conduit/wind_vertical")
            ),
            () -> SodiumExtraClientMod.options().animationSettings.sculkSensor, List.of(
                    Identifier.fromNamespaceAndPath("minecraft", "block/sculk"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/sculk_catalyst_top_bloom"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/sculk_catalyst_side_bloom"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/sculk_shrieker_inner_top"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/sculk_vein"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/sculk_shrieker_can_summon_inner_top"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/sculk_sensor_tendril_inactive"),
                    Identifier.fromNamespaceAndPath("minecraft", "block/sculk_sensor_tendril_active"),
                    Identifier.fromNamespaceAndPath("minecraft", "vibration")
            )
    );

    private AnimatedSpriteFilter() {
    }

    public static boolean shouldAnimate(Identifier identifier) {
        if (identifier != null) {
            for (Map.Entry<Supplier<Boolean>, List<Identifier>> supplierListEntry : ANIMATED_SPRITES.entrySet()) {
                if (supplierListEntry.getValue().contains(identifier)) {
                    return supplierListEntry.getKey().get();
                }
            }
        }
        return true;
    }
}
