package com.mentalfrostbyte.jello.module.impl.combat.aimassist;

/** Time-based camera steering, in degrees, independent of Minecraft and mouse sensitivity. */
public final class AimSteering {
    public record Turn(double yaw, double pitch) { }

    private AimSteering() { }

    /** Discard stalls instead of catching up a whole pause with a large camera jump. */
    public static double frameSeconds(double seconds) {
        return Double.isFinite(seconds) && seconds > 0.0 && seconds <= 0.25
                ? Math.min(seconds, 0.05) : 0.0;
    }

    public static double wrap(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    /** Proximity friction is weakest far from the target and strongest at its centre. */
    public static double mouseScale(double error, double strength) {
        double near = 1.0 - clamp(error / 12.0, 0.0, 1.0);
        return 1.0 - 0.75 * clamp(strength, 0.0, 1.0) * near * near;
    }

    public static Turn lock(double yawError, double pitchError, double speed, double seconds) {
        double dt = frameSeconds(seconds);
        double distance = Math.hypot(yawError, pitchError);
        if (distance < 1.0E-5 || dt == 0.0) return new Turn(0, 0);
        double step = Math.min(distance * -Math.expm1(-24.0 * dt), Math.max(0, speed) * dt);
        return new Turn(yawError * step / distance, pitchError * step / distance);
    }

    public static Turn assist(double yawError, double pitchError, double mouseYaw, double mousePitch,
                              double speed, double strength, double seconds) {
        double dt = frameSeconds(seconds);
        double scale = mouseScale(Math.hypot(yawError, pitchError), strength);
        double yaw = mouseYaw * scale, pitch = mousePitch * scale;
        double remainingYaw = yawError - yaw, remainingPitch = pitchError - pitch;
        double distance = Math.hypot(remainingYaw, remainingPitch);
        if (distance > 1.0E-5 && dt > 0.0) {
            double step = Math.min(distance * -Math.expm1(-6.0 * strength * dt), speed * strength * dt);
            yaw += remainingYaw * step / distance;
            pitch += remainingPitch * step / distance;
        }
        return new Turn(yaw, pitch);
    }

    /** Bound learned movement along each axis so noise cannot move away from, or through, the aim point. */
    public static Turn learned(double yawError, double pitchError, double yawVelocity, double pitchVelocity,
                               double speed, double seconds) {
        double dt = frameSeconds(seconds);
        double length = Math.hypot(yawVelocity, pitchVelocity);
        double scale = length > speed ? speed / length : 1.0;
        return new Turn(toward(yawError, yawVelocity * scale * dt), toward(pitchError, pitchVelocity * scale * dt));
    }

    private static double toward(double error, double step) {
        if (!Double.isFinite(step) || error * step <= 0.0) return 0.0;
        return Math.copySign(Math.min(Math.abs(error), Math.abs(step)), error);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
