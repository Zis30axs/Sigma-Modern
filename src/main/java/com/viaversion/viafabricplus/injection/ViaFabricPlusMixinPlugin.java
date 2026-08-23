/*
 * This file is part of ViaFabricPlus - https://github.com/ViaVersion/ViaFabricPlus
 * Copyright (C) 2021-2026 the original authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.viaversion.viafabricplus.injection;

import com.viaversion.viafabricplus.features.movement.constants.LithiumWorkaround;
import com.viaversion.viafabricplus.features.movement.elytra.FabricAPIWorkaround;
import net.fabricmc.loader.api.FabricLoader;

// MODIFIED for porting: was IMixinConfigPlugin - mixin application is replaced by inline hooks, this class only
// carries the environment flags and the per-mixin gating decisions for those hooks.
public final class ViaFabricPlusMixinPlugin {

    private static final String MIXINS_PACKAGE = "com.viaversion.viafabricplus.injection.mixin.";

    public static boolean IPNEXT_PRESENT;
    public static boolean MORE_CULLING_PRESENT;
    public static boolean LITHIUM_PRESENT;
    public static boolean MOONRISE_PRESENT;
    public static boolean LEGENDARYTOOLTIPS_PRESENT;
    public static boolean LEGACY_PRESENT;

    // MODIFIED for porting: was IMixinConfigPlugin#onLoad
    public static void onLoad() {
        final FabricLoader loader = FabricLoader.getInstance();
        IPNEXT_PRESENT = loader.isModLoaded("inventoryprofilesnext");
        MORE_CULLING_PRESENT = loader.isModLoaded("moreculling");
        LITHIUM_PRESENT = loader.isModLoaded("lithium");
        MOONRISE_PRESENT = loader.isModLoaded("moonrise");
        LEGENDARYTOOLTIPS_PRESENT = loader.isModLoaded("legendarytooltips");
        LEGACY_PRESENT = loader.isModLoaded("legacy");

        FabricAPIWorkaround.init();
        LithiumWorkaround.init();
    }

    // MODIFIED for porting: was IMixinConfigPlugin#shouldApplyMixin
    public static boolean shouldApplyMixin(final String mixinClassName) {
        return switch (mixinClassName) {
            case MIXINS_PACKAGE + "compat.ipnext.MixinAutoRefillHandler_ItemSlotMonitor" -> IPNEXT_PRESENT;
            case MIXINS_PACKAGE + "compat.lithium.MixinEntity" -> LITHIUM_PRESENT && !MOONRISE_PRESENT;
            case MIXINS_PACKAGE + "features.item.attack_damage.MixinItemStack" -> !LEGENDARYTOOLTIPS_PRESENT;
            case MIXINS_PACKAGE + "features.item.negative_item_count.MixinGuiGraphics" -> !LEGACY_PRESENT;
            default -> true;
        };
    }

    private ViaFabricPlusMixinPlugin() {
    }
}