package com.mentalfrostbyte.jello.util.game;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Bounded flight prediction against read-only world queries; never creates a projectile or changes the world. */
public final class ProjectileTrajectories {
    public static final int MAX_TICKS = 240;
    public static final double MAX_RANGE = 256;

    public record Path(List<Vec3> points, @Nullable Vec3 hitPosition, @Nullable Direction hitFace,
                       boolean entityHit, boolean preview) {
        public Path { points = List.copyOf(points); }
    }

    public record Hit(Vec3 position, @Nullable Direction face, boolean entityHit) {}

    interface Environment {
        boolean loaded(Vec3 from, Vec3 to);
        boolean water(Vec3 position, ProjectileLaunch.Shot shot);
        @Nullable Hit collision(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick);
    }

    private ProjectileTrajectories() {}

    public static List<Path> predict(LocalPlayer player, float partialTicks, float yaw, float pitch, double range) {
        if (player == null || !player.isAlive() || player.isSpectator() || !Double.isFinite(range) || range <= 0) {
            return List.of();
        }
        List<ProjectileLaunch.Shot> shots = ProjectileLaunch.held(player, partialTicks, yaw, pitch);
        if (shots.isEmpty()) return List.of();
        Environment world = new TrajectoryWorld(player, partialTicks);
        List<Path> paths = new ArrayList<>(Math.min(3, shots.size()));
        for (int i = 0; i < Math.min(3, shots.size()); i++) {
            Path path = simulate(shots.get(i), world, range);
            if (path.points.size() > 1) paths.add(path);
        }
        return List.copyOf(paths);
    }

    /** Simulates the center trajectory; random shot spread and future target motion are deliberately not invented. */
    static Path simulate(ProjectileLaunch.Shot shot, Environment world, double requestedRange) {
        if (!valid(shot) || !Double.isFinite(requestedRange) || requestedRange <= 0) {
            return new Path(List.of(), null, null, false, shot != null && shot.preview());
        }
        Vec3 position = shot.origin();
        Vec3 velocity = shot.velocity();
        List<Vec3> points = new ArrayList<>();
        points.add(position);
        double remaining = Math.min(MAX_RANGE, requestedRange);
        int ticks = Math.min(MAX_TICKS, Math.max(0, shot.maxTicks()));
        for (int tick = 0; tick < ticks && remaining > 1.0E-6; tick++) {
            if (!world.loaded(position, position)) break;
            // New projectiles start with wasTouchingWater=false; baseTick samples their first medium after moving.
            boolean water = tick > 0 && world.water(position, shot);
            double drag = water ? shot.waterDrag() : shot.airDrag();
            // 26.2 throwables apply gravity, then inertia, BEFORE moving. Arrows move with the old velocity.
            if (shot.physics() == ProjectileLaunch.Physics.THROWABLE) {
                velocity = velocity.add(0, -shot.gravity(), 0).scale(drag);
            } else if (shot.physics() == ProjectileLaunch.Physics.WIND_CHARGE) {
                velocity = velocity.scale(drag);
            }
            double speed = velocity.length();
            if (!finite(velocity) || !Double.isFinite(speed) || speed < 1.0E-8) break;
            boolean rangeEnd = speed >= remaining;
            Vec3 next = position.add(velocity.scale(rangeEnd ? remaining / speed : 1));
            if (!finite(next) || !world.loaded(position, next)) break;
            Hit hit = world.collision(position, next, shot, tick);
            if (hit != null) {
                if (!finite(hit.position)) break;
                points.add(hit.position);
                return new Path(points, hit.position, hit.face, hit.entityHit, shot.preview());
            }
            points.add(next);
            remaining -= position.distanceTo(next);
            position = next;
            if (rangeEnd) break;
            if (shot.physics() == ProjectileLaunch.Physics.ARROW) {
                velocity = velocity.scale(drag).add(0, -shot.gravity(), 0);
            }
            // A crossbow firework (shotAtAngle) and a wind charge have no ballistic gravity or acceleration.
        }
        // Reaching the horizon, range or unloaded terrain is not a predicted collision.
        return new Path(points, null, null, false, shot.preview());
    }

    private static boolean valid(ProjectileLaunch.Shot shot) {
        return shot != null && shot.physics() != null && finite(shot.origin()) && finite(shot.velocity())
                && Double.isFinite(shot.gravity()) && shot.gravity() >= 0 && shot.gravity() <= 1
                && Double.isFinite(shot.airDrag()) && shot.airDrag() > 0 && shot.airDrag() <= 1
                && Double.isFinite(shot.waterDrag()) && shot.waterDrag() > 0 && shot.waterDrag() <= 1
                && Float.isFinite(shot.width()) && shot.width() > 0 && shot.width() <= 4
                && Float.isFinite(shot.height()) && shot.height() > 0 && shot.height() <= 4;
    }

    static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
