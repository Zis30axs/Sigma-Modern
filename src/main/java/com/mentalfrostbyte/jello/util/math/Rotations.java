package com.mentalfrostbyte.jello.util.math;

import java.util.Optional;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Look directions for modules that aim: where to face to look at something, how to turn there a step at a time, and
 * what a turn made with a real mouse looks like. Pure maths on vanilla's vectors, so it is testable without a game.
 *
 * <p>Yaw is kept continuous, the way the client itself keeps it: a player who turns twice round has a yaw of 720, and
 * the server is told 720, not 0. {@link #towards} is the one place that yields a wrapped yaw (atan2's
 * {@code (-180, 180]}); {@link #continuous} puts such a yaw on the turn nearest a given one.</p>
 */
public final class Rotations {

    /** A look direction; yaw in degrees, not wrapped, pitch in degrees, positive looking down. */
    public record Rotation(float yaw, float pitch) {
    }

    private Rotations() {
    }

    /** Facing {@code point} from {@code eye}: yaw wrapped to {@code (-180, 180]}, pitch in {@code [-90, 90]}. */
    public static Rotation towards(final Vec3 eye, final Vec3 point) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new Rotation(yaw, Mth.clamp(pitch, -90.0F, 90.0F));
    }

    /** {@code target} moved by whole turns to lie within half a turn of {@code from}. */
    public static float continuous(final float from, final float target) {
        return from + Mth.wrapDegrees(target - from);
    }

    /** {@code target} with its yaw made {@link #continuous} with {@code from}'s. */
    public static Rotation continuous(final Rotation from, final Rotation target) {
        return new Rotation(continuous(from.yaw(), target.yaw()), target.pitch());
    }

    /** One step from {@code from} towards {@code to} (continuous), turning at most {@code maxYaw} and {@code maxPitch}. */
    public static Rotation limit(final Rotation from, final Rotation to, final float maxYaw, final float maxPitch) {
        float yaw = from.yaw() + Mth.clamp(to.yaw() - from.yaw(), -maxYaw, maxYaw);
        float pitch = from.pitch() + Mth.clamp(to.pitch() - from.pitch(), -maxPitch, maxPitch);
        return new Rotation(yaw, pitch);
    }

    /**
     * The smallest turn the vanilla mouse makes at {@code sensitivity} (the option, 0 to 1), in degrees: every turn a
     * mouse makes is a whole number of these ({@code MouseHandler.turnPlayer} and {@code Entity.turn}).
     */
    public static double mouseStep(final double sensitivity) {
        double f = sensitivity * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }

    /** The turn from {@code from} to {@code to} rounded to whole mouse steps, as a mouse would have made it. */
    public static Rotation quantize(final Rotation from, final Rotation to, final double step) {
        float yaw = (float) (from.yaw() + MathUtil.roundToStep(to.yaw() - from.yaw(), step));
        float pitch = (float) (from.pitch() + MathUtil.roundToStep(to.pitch() - from.pitch(), step));
        return new Rotation(yaw, Mth.clamp(pitch, -90.0F, 90.0F));
    }

    /**
     * The point of {@code box}, shrunk by {@code inset} on every side, nearest to {@code eye} - the least turn that
     * still looks at the box. The centre when the eye is right above or below it, where no yaw would be defined.
     */
    public static Vec3 nearestPoint(final Vec3 eye, final AABB box, final double inset) {
        AABB inner = box.deflate(Math.min(inset, Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) / 2.0 - 1.0E-4));
        Vec3 point = new Vec3(Mth.clamp(eye.x, inner.minX, inner.maxX), Mth.clamp(eye.y, inner.minY, inner.maxY),
                Mth.clamp(eye.z, inner.minZ, inner.maxZ));
        if (Math.abs(point.x - eye.x) < 1.0E-3 && Math.abs(point.z - eye.z) < 1.0E-3) {
            return box.getCenter();
        }
        return point;
    }

    /** Where a look from {@code eye} along {@code rotation} first meets {@code box} within {@code range}, if it does. */
    public static Optional<Vec3> hit(final Vec3 eye, final Rotation rotation, final AABB box, final double range) {
        if (box.contains(eye)) {
            return Optional.of(eye);
        }
        Vec3 end = eye.add(Vec3.directionFromRotation(rotation.pitch(), rotation.yaw()).scale(range));
        return box.clip(eye, end);
    }
}
