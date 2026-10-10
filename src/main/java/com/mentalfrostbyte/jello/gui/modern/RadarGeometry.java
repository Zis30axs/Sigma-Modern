package com.mentalfrostbyte.jello.gui.modern;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Radar and warning bearings in the player's horizontal frame, independent of rendering or live entities. */
public final class RadarGeometry {
    private static final double MIN_DISTANCE = 1.0E-8;
    private static final double HEAD_ON_SPEED = 0.02;

    private RadarGeometry() {}

    /** Positive right points to screen-right, positive forward to screen-top; distance is horizontal, elevation is height. */
    public record Relative(double right, double forward, double distance, double bearingDegrees, double elevation) {}

    /** Minecraft yaw 0 faces +Z, yaw 90 faces -X. Bearings are clockwise in [-180, 180]. */
    public static @Nullable Relative relative(final Vec3 offset, final float yaw) {
        if (!finite(offset) || !Float.isFinite(yaw)) return null;
        double radians = Math.toRadians(yaw % 360.0);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double right = -offset.x * cos - offset.z * sin;
        double forward = -offset.x * sin + offset.z * cos;
        double distance = Math.hypot(offset.x, offset.z);
        if (!Double.isFinite(right) || !Double.isFinite(forward) || !Double.isFinite(distance)) return null;
        double bearing = distance < MIN_DISTANCE ? 0.0 : Math.toDegrees(Math.atan2(right, forward));
        return new Relative(right, forward, distance, bearing, offset.y);
    }

    /** Contacts outside the sector or range are rejected rather than squeezed onto its edge. */
    public static boolean withinScan(final @Nullable Relative contact, final double range, final double scanAngleDegrees) {
        if (contact == null || !valid(contact) || !Double.isFinite(range) || range <= 0.0
                || !Double.isFinite(scanAngleDegrees) || scanAngleDegrees <= 0.0 || scanAngleDegrees > 180.0) return false;
        return contact.distance() > MIN_DISTANCE && contact.distance() <= range
                && Math.abs(contact.bearingDegrees()) <= scanAngleDegrees * 0.5 + 1.0E-8;
    }

    /**
     * Positive closing speed means approaching, in the supplied velocity units (normally blocks per tick).
     * The offset points from observer to contact; relativeVelocity is contact velocity minus observer velocity.
     */
    public static double closure(final Vec3 offset, final Vec3 relativeVelocity) {
        if (!finite(offset) || !finite(relativeVelocity)) return 0.0;
        double distance = Math.hypot(Math.hypot(offset.x, offset.y), offset.z);
        if (!Double.isFinite(distance) || distance < MIN_DISTANCE) return 0.0;
        double closing = -(offset.x / distance * relativeVelocity.x
                + offset.y / distance * relativeVelocity.y + offset.z / distance * relativeVelocity.z);
        return Double.isFinite(closing) ? closing : 0.0;
    }

    /**
     * A gameplay approximation of HDN: the contact itself must move toward the observer and close the gap.
     * Chasing a stationary or retreating contact is therefore not reported as a head-on approach.
     */
    public static boolean isHeadOn(final Vec3 offset, final Vec3 targetVelocity, final Vec3 observerVelocity) {
        if (!finite(offset) || !finite(targetVelocity) || !finite(observerVelocity)) return false;
        return closure(offset, targetVelocity) > HEAD_ON_SPEED
                && closure(offset, targetVelocity.subtract(observerVelocity)) > HEAD_ON_SPEED;
    }

    private static boolean finite(final Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean valid(final Relative contact) {
        return Double.isFinite(contact.right()) && Double.isFinite(contact.forward())
                && Double.isFinite(contact.distance()) && contact.distance() >= 0.0
                && Double.isFinite(contact.bearingDegrees()) && Double.isFinite(contact.elevation());
    }
}
