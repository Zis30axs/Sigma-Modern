package net.caffeinemc.mods.lithium.common.world.listeners;

import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import org.jetbrains.annotations.NotNull;

import java.util.WeakHashMap;

public class WorldBorderPositionListenerMulti implements BorderChangeListener {

    private final WeakHashMap<WorldBorderListenerOnce, Object> delegate;

    public WorldBorderPositionListenerMulti() {
        this.delegate = new WeakHashMap<>();
    }

    public void add(WorldBorderListenerOnce listener) {
        this.delegate.put(listener, null);
    }

    public void onAreaReplaced(WorldBorder border) {
        for (WorldBorderListenerOnce listener : this.delegate.keySet()) {
            listener.onAreaReplaced(border);
        }
        this.delegate.clear();
    }

    @Override
    public void onSetSize(@NotNull WorldBorder border, double size) {
        for (WorldBorderListenerOnce listener : this.delegate.keySet()) {
            listener.onSetSize(border, size);
        }
        this.delegate.clear();
    }

    @Override
    public void onLerpSize(@NotNull WorldBorder border, double fromSize, double toSize, long ticks, long gameTime) {
        for (WorldBorderListenerOnce listener : this.delegate.keySet()) {
            listener.onLerpSize(border, fromSize, toSize, ticks, gameTime);
        }
        this.delegate.clear();
    }

    @Override
    public void onSetCenter(@NotNull WorldBorder border, double centerX, double centerZ) {
        for (WorldBorderListenerOnce listener : this.delegate.keySet()) {
            listener.onSetCenter(border, centerX, centerZ);
        }
        this.delegate.clear();
    }

    @Override
    public void onSetWarningTime(@NotNull WorldBorder border, int warningTime) {
    }

    @Override
    public void onSetWarningBlocks(@NotNull WorldBorder border, int warningBlockDistance) {
    }

    @Override
    public void onSetDamagePerBlock(@NotNull WorldBorder border, double damagePerBlock) {
    }

    @Override
    public void onSetSafeZone(@NotNull WorldBorder border, double safeZoneRadius) {
    }
}
