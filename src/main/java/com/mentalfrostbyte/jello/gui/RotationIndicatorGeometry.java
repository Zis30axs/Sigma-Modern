package com.mentalfrostbyte.jello.gui;

import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Maps an actual world-facing direction to a bounded offset from the camera's crosshair. */
public final class RotationIndicatorGeometry {
    private static final double DEAD_ZONE = Math.toRadians(0.5);
    private static final double HALF_PI = Math.PI / 2.0;
    private static final double DIRECTION_EPSILON = 1.0e-6;

    private RotationIndicatorGeometry() {}

    /** Screen-space offset: positive x is right and positive y is down. */
    public record Pointer(float x, float y, boolean behind) {}

    /**
     * The matrix is the camera's world-to-view rotation, without projection. Minecraft yaw and
     * pitch are in degrees, with yaw zero along +Z and positive pitch looking down. Translation
     * is deliberately ignored: the input is a direction, not a position.
     */
    public static @Nullable Pointer project(Matrix4fc viewRotation, float yaw, float pitch, float radius) {
        if (viewRotation == null || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Float.isFinite(radius) || radius <= 0.0F) return null;

        // Equivalent to Vec3.directionFromRotation(pitch, yaw), without a game-state dependency.
        double yawRadians = Math.toRadians(Math.IEEEremainder(yaw, 360.0));
        double pitchRadians = Math.toRadians(Math.IEEEremainder(pitch, 360.0));
        double horizontal = Math.cos(pitchRadians);
        double worldX = -Math.sin(yawRadians) * horizontal;
        double worldY = -Math.sin(pitchRadians);
        double worldZ = Math.cos(yawRadians) * horizontal;
        double viewX = viewRotation.m00() * worldX + viewRotation.m10() * worldY + viewRotation.m20() * worldZ;
        double viewY = viewRotation.m01() * worldX + viewRotation.m11() * worldY + viewRotation.m21() * worldZ;
        double viewZ = viewRotation.m02() * worldX + viewRotation.m12() * worldY + viewRotation.m22() * worldZ;
        if (!Double.isFinite(viewX) || !Double.isFinite(viewY) || !Double.isFinite(viewZ)) return null;

        double lateral = Math.hypot(viewX, viewY);
        double magnitude = Math.hypot(lateral, viewZ);
        if (magnitude <= DIRECTION_EPSILON) return null;
        double angle = Math.atan2(lateral, -viewZ);
        if (angle < DEAD_ZONE) return null;

        boolean behind = viewZ > DIRECTION_EPSILON * magnitude;
        double length = radius * Math.min(1.0, angle / HALF_PI);
        // At exactly 180 degrees, floating-point trig noise has no meaningful left/right sign.
        // Choose a stable rightward cue instead of flickering or disappearing at the crosshair.
        if (lateral <= DIRECTION_EPSILON * magnitude) return new Pointer((float) length, 0.0F, behind);
        return new Pointer((float) (viewX / lateral * length), (float) (-viewY / lateral * length), behind);
    }
}
