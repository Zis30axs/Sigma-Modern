package com.mentalfrostbyte.jello.util.game;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Deterministic interception in air, using the same discrete tick order as projectile previews. */
public final class ProjectileAim {
    public static final int MAX_TICKS = 160;
    public static final double MAX_RANGE = 256;
    public static final double MAX_PREDICTION_TICKS = 30;
    public static final double MAX_PREDICTION_DISTANCE = 24;
    public static final double MAX_TARGET_SPEED = 1.5;
    private static final double STEP = 0.25;
    private static final double WORLD_LIMIT = 30_000_000;

    public record Input(ProjectileLaunch.Template template, Vec3 eye, Vec3 inheritedMovement,
                        Vec3 targetPoint, Vec3 targetVelocity) { }

    /** Path nodes are integer ticks followed by the fractional intercept time; hitPoint is the checked endpoint. */
    public record Aim(float yaw, float pitch, double timeTicks, Vec3 hitPoint,
                      List<Vec3> path, ProjectileLaunch.Shot shot, Vec3 targetPoint, Vec3 targetVelocity) {
        public Aim { path = List.copyOf(path); }
    }

    private record Coefficients(double[] travel, double[] drop) {
        double travel(double time) { return interpolate(travel, time); }
        double drop(double time) { return interpolate(drop, time); }
        private static double interpolate(double[] values, double time) {
            int tick = Math.min(values.length - 1, (int) time);
            return tick == values.length - 1 ? values[tick]
                    : values[tick] + (values[tick + 1] - values[tick]) * (time - tick);
        }
    }

    private ProjectileAim() { }

    /** No world access or firing occurs here. The caller must validate every path segment before firing. */
    public static @Nullable Aim solve(Input input) {
        List<Aim> solutions = solveArcs(input, false);
        return solutions.isEmpty() ? null : solutions.getFirst();
    }

    /** Low and high arcs, in flight-time order. Each path still needs a world collision check. */
    public static List<Aim> solveAll(Input input) {
        return solveArcs(input, true);
    }

    private static List<Aim> solveArcs(Input input, boolean includeHighArc) {
        if (!valid(input)) return List.of();
        ProjectileLaunch.Template template = input.template();
        Vec3 origin = input.eye().add(0, template.originY(), 0);
        if (origin.distanceToSqr(input.targetPoint()) < 1.0E-8
                || origin.distanceToSqr(input.targetPoint()) > MAX_RANGE * MAX_RANGE) return List.of();
        Vec3 inherited = template.inheritsMovement() ? input.inheritedMovement() : Vec3.ZERO;
        int horizon = Math.min(MAX_TICKS, template.maxTicks());
        Coefficients coefficients = coefficients(template, horizon);
        Vec3 velocity = boundedVelocity(input.targetVelocity());
        double previousTime = 1.0E-4;
        double previous = speedError(input, origin, inherited, velocity, coefficients, previousTime);
        List<Aim> solutions = new ArrayList<>(2);
        for (double time = STEP; time <= horizon; time += STEP) {
            double error = speedError(input, origin, inherited, velocity, coefficients, time);
            boolean lowArc = previous >= 0 && error <= 0;
            if (lowArc || (includeHighArc && previous < 0 && error >= 0)) {
                double left = previousTime;
                double right = time;
                for (int iteration = 0; iteration < 44; iteration++) {
                    double middle = (left + right) * 0.5;
                    if ((speedError(input, origin, inherited, velocity, coefficients, middle) > 0) == lowArc) left = middle;
                    else right = middle;
                }
                Aim aim = result(input, origin, inherited, velocity, coefficients, (left + right) * 0.5);
                if (aim != null) solutions.add(aim);
                if (!includeHighArc || solutions.size() == 2) return List.copyOf(solutions);
            }
            previousTime = time;
            previous = error;
        }
        return List.copyOf(solutions);
    }

    /**
     * Rebuilds the flight from a fresh launch position while preserving the facing used for this tick's movement.
     * The endpoint can differ from the ideal target center; a world validator must confirm it is in the future box.
     */
    public static @Nullable Aim withRotation(Input input, float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90) return null;
        Aim ideal = solve(input);
        if (ideal == null) return null;
        Coefficients coefficients = coefficients(input.template(), Math.min(MAX_TICKS, input.template().maxTicks()));
        return flight(input, ideal.targetVelocity(), coefficients, ideal.timeTicks(), yaw, pitch, null);
    }

    /** Shared bounded lead for future target-box checks performed by the firing caller. */
    public static Vec3 predictedTarget(Vec3 point, Vec3 velocity, double timeTicks) {
        if (!finite(point) || !finite(velocity) || !Double.isFinite(timeTicks)) return point;
        Vec3 bounded = boundedVelocity(velocity);
        return point.add(bounded.scale(predictionTime(bounded, timeTicks)));
    }

    private static double predictionTime(Vec3 velocity, double time) {
        double speed = velocity.length();
        return Math.max(0, Math.min(time, Math.min(MAX_PREDICTION_TICKS,
                speed > 1.0E-8 ? MAX_PREDICTION_DISTANCE / speed : MAX_PREDICTION_TICKS)));
    }

    private static Vec3 boundedVelocity(Vec3 velocity) {
        double speed = velocity.length();
        return speed > MAX_TARGET_SPEED ? velocity.scale(MAX_TARGET_SPEED / speed) : velocity;
    }

    private static double speedError(Input input, Vec3 origin, Vec3 inherited, Vec3 targetVelocity,
                                     Coefficients coefficients, double time) {
        double lead = predictionTime(targetVelocity, time);
        double travel = coefficients.travel(time);
        double vx = (input.targetPoint().x + targetVelocity.x * lead - origin.x) / travel - inherited.x;
        double vy = (input.targetPoint().y + targetVelocity.y * lead - origin.y - coefficients.drop(time)) / travel - inherited.y;
        double vz = (input.targetPoint().z + targetVelocity.z * lead - origin.z) / travel - inherited.z;
        double speed = input.template().speed();
        return vx * vx + vy * vy + vz * vz - speed * speed;
    }

    private static @Nullable Aim result(Input input, Vec3 origin, Vec3 inherited, Vec3 targetVelocity,
                                        Coefficients coefficients, double time) {
        Vec3 hit = input.targetPoint().add(targetVelocity.scale(predictionTime(targetVelocity, time)));
        Vec3 launch = hit.subtract(origin).add(0, -coefficients.drop(time), 0)
                .scale(1 / coefficients.travel(time)).subtract(inherited);
        // A moving target is not assumed to stop when the prediction horizon expires.
        if (targetVelocity.lengthSqr() > 1.0E-8 && time > predictionTime(targetVelocity, time) + 1.0E-6) return null;
        double horizontal = launch.horizontalDistance();
        float yaw = (float) Math.toDegrees(Math.atan2(-launch.x, launch.z));
        double offset = Math.toRadians(input.template().pitchOffset());
        // shootFromRotation offsets only Y. Applying -20 degrees to the whole pitch aims potions incorrectly.
        float pitch = horizontal < 1.0E-10 ? (launch.y >= 0 ? -90F : 90F)
                : (float) Math.toDegrees(Math.atan((-launch.y / horizontal - Math.sin(offset)) / Math.cos(offset)));
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90) return null;
        return flight(input, targetVelocity, coefficients, time, yaw, pitch, hit);
    }

    private static @Nullable Aim flight(Input input, Vec3 targetVelocity, Coefficients coefficients,
                                        double time, float yaw, float pitch, @Nullable Vec3 expectedCenter) {
        ProjectileLaunch.Shot shot = input.template().launch(input.eye(), input.inheritedMovement(), yaw, pitch);
        Vec3 origin = shot.origin();
        List<Vec3> path = new ArrayList<>((int) Math.ceil(time) + 1);
        path.add(origin);
        double length = 0;
        int lastTick = (int) Math.ceil(time);
        for (int tick = 1; tick <= lastTick; tick++) {
            double at = Math.min(time, tick);
            Vec3 point = origin.add(shot.velocity().scale(coefficients.travel(at))).add(0, coefficients.drop(at), 0);
            length += path.getLast().distanceTo(point);
            if (!position(point) || length > MAX_RANGE) return null;
            path.add(point);
        }
        // Vanilla's trigonometric table and float yaw/pitch introduce tiny rounding differences.
        if (expectedCenter != null && path.getLast().distanceToSqr(expectedCenter) > 0.0025) return null;
        Vec3 hit = expectedCenter != null ? expectedCenter : path.getLast();
        return new Aim(yaw, pitch, time, hit, path, shot, input.targetPoint(), targetVelocity);
    }

    private static Coefficients coefficients(ProjectileLaunch.Template template, int ticks) {
        double[] travel = new double[ticks + 1];
        double[] drop = new double[ticks + 1];
        double velocityFactor = 1;
        double verticalVelocity = 0;
        for (int tick = 0; tick < ticks; tick++) {
            if (template.physics() == ProjectileLaunch.Physics.THROWABLE) {
                velocityFactor *= template.airDrag();
                verticalVelocity = (verticalVelocity - template.gravity()) * template.airDrag();
            } else if (template.physics() == ProjectileLaunch.Physics.WIND_CHARGE) {
                velocityFactor *= template.airDrag();
            }
            travel[tick + 1] = travel[tick] + velocityFactor;
            drop[tick + 1] = drop[tick] + verticalVelocity;
            if (template.physics() == ProjectileLaunch.Physics.ARROW) {
                velocityFactor *= template.airDrag();
                verticalVelocity = verticalVelocity * template.airDrag() - template.gravity();
            }
        }
        return new Coefficients(travel, drop);
    }

    private static boolean valid(Input input) {
        if (input == null || input.template() == null || !position(input.eye()) || !position(input.targetPoint())
                || !finite(input.inheritedMovement()) || input.inheritedMovement().lengthSqr() > 256
                || !finite(input.targetVelocity()) || input.targetVelocity().lengthSqr() > 1.0E6) return false;
        ProjectileLaunch.Template template = input.template();
        return template.physics() != null && Float.isFinite(template.speed()) && template.speed() > 0.01F && template.speed() <= 10
                && Float.isFinite(template.pitchOffset()) && Math.abs(template.pitchOffset()) <= 45
                && Double.isFinite(template.gravity()) && template.gravity() >= 0 && template.gravity() <= 1
                && Double.isFinite(template.airDrag()) && template.airDrag() >= 0.01 && template.airDrag() <= 1
                && Double.isFinite(template.waterDrag()) && template.waterDrag() > 0 && template.waterDrag() <= 1
                && Double.isFinite(template.originY()) && Math.abs(template.originY()) <= 4
                && Float.isFinite(template.width()) && template.width() > 0 && template.width() <= 4
                && Float.isFinite(template.height()) && template.height() > 0 && template.height() <= 4
                && template.maxTicks() > 0;
    }

    private static boolean position(Vec3 point) {
        return finite(point) && Math.abs(point.x) < WORLD_LIMIT && Math.abs(point.y) < WORLD_LIMIT && Math.abs(point.z) < WORLD_LIMIT;
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
