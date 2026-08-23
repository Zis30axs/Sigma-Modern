package com.mojang.blaze3d.systems;

import java.util.Set;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record DeviceInfo(
    String name,
    String vendorName,
    String driverInfo,
    boolean isZZeroToOne,
    String backendName,
    float timestampPeriod,
    DeviceLimits limits,
    DeviceFeatures features,
    Set<String> underlyingExtensions,
    HintsAndWorkarounds hintsAndWorkarounds,
    DeviceType type
) {
    /**
     * MODIFIED for porting: was iris's UndoReverseZOne#iris$force (@Inject HEAD, cancellable). Shader packs are written
     * against OpenGL's classic [-1, 1] depth range, so with a pack loaded iris undoes vanilla's reversed-Z setup (see the
     * other {@code UndoReverseZ*} conversions).
     */
    public boolean isZZeroToOne() {
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.Iris.isPackInUseQuick()) {
            return false;
        }

        return this.isZZeroToOne;
    }
}