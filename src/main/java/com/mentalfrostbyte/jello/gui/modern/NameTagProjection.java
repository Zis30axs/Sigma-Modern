package com.mentalfrostbyte.jello.gui.modern;

import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Projects camera-relative positions without depending on Minecraft or a render context. */
public final class NameTagProjection {
    private NameTagProjection() {}

    public record Point(float x, float y) {}

    /** Uses the zero-to-one depth range of the standard reverse-Z world projection. */
    public static @Nullable Point project(Matrix4fc worldToClip, double relativeX, double relativeY,
                                         double relativeZ, int guiWidth, int guiHeight) {
        return project(worldToClip, relativeX, relativeY, relativeZ, guiWidth, guiHeight, true);
    }

    /**
     * The depth range must match the projection: Iris shader packs may use negative-one-to-one Z.
     * X/Y deliberately remain unclipped so a card whose anchor is offscreen can still overlap the view.
     */
    public static @Nullable Point project(Matrix4fc worldToClip, double relativeX, double relativeY,
                                         double relativeZ, int guiWidth, int guiHeight, boolean zZeroToOne) {
        if (guiWidth <= 0 || guiHeight <= 0 || !Double.isFinite(relativeX)
                || !Double.isFinite(relativeY) || !Double.isFinite(relativeZ)) return null;

        // Subtract the camera in double precision before calling this method, especially far from spawn.
        double clipX = worldToClip.m00() * relativeX + worldToClip.m10() * relativeY
                + worldToClip.m20() * relativeZ + worldToClip.m30();
        double clipY = worldToClip.m01() * relativeX + worldToClip.m11() * relativeY
                + worldToClip.m21() * relativeZ + worldToClip.m31();
        double clipZ = worldToClip.m02() * relativeX + worldToClip.m12() * relativeY
                + worldToClip.m22() * relativeZ + worldToClip.m32();
        double clipW = worldToClip.m03() * relativeX + worldToClip.m13() * relativeY
                + worldToClip.m23() * relativeZ + worldToClip.m33();
        if (!Double.isFinite(clipX) || !Double.isFinite(clipY) || !Double.isFinite(clipZ)
                || !Double.isFinite(clipW) || clipW <= 0.0) return null;
        if (clipZ < (zZeroToOne ? 0.0 : -clipW) || clipZ > clipW) return null;

        float x = (float) ((clipX / clipW * 0.5 + 0.5) * guiWidth);
        float y = (float) ((0.5 - clipY / clipW * 0.5) * guiHeight);
        return Float.isFinite(x) && Float.isFinite(y) ? new Point(x, y) : null;
    }
}
