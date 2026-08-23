package net.caffeinemc.mods.lithium.common.world.listeners;

import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import org.jetbrains.annotations.NotNull;

public interface WorldBorderListenerOnce extends BorderChangeListener {

    default void lithium$onWorldBorderShapeChange(WorldBorder worldBorder) {

    }

    default void onAreaReplaced(WorldBorder border) {
        this.lithium$onWorldBorderShapeChange(border);
    }

    @Override
    default void onSetSize(@NotNull WorldBorder border, double size) {
        this.lithium$onWorldBorderShapeChange(border);
    }

    @Override
    default void onLerpSize(@NotNull WorldBorder border, double fromSize, double toSize, long ticks, long gameTime) {
        this.lithium$onWorldBorderShapeChange(border);
    }

    @Override
    default void onSetCenter(@NotNull WorldBorder border, double centerX, double centerZ) {
        this.lithium$onWorldBorderShapeChange(border);
    }

    @Override
    default void onSetWarningTime(@NotNull WorldBorder border, int warningTime) {

    }

    @Override
    default void onSetWarningBlocks(@NotNull WorldBorder border, int warningBlockDistance) {

    }

    @Override
    default void onSetDamagePerBlock(@NotNull WorldBorder border, double damagePerBlock) {

    }

    @Override
    default void onSetSafeZone(@NotNull WorldBorder border, double safeZoneRadius) {

    }
}
