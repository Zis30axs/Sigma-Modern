package com.mentalfrostbyte.jello.util.game;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Read-only, bounded world checks for one already solved shot. Never loads terrain or creates a projectile. */
public final class ProjectileAimValidator {
    private static final double HIT_TOLERANCE = 0.05;
    private static final int MAX_OBSTACLES = 4096;

    private record Obstacle(AABB bounds, Vec3 velocity) { }

    private ProjectileAimValidator() { }

    public static boolean clear(LocalPlayer player, LivingEntity target, ProjectileAim.Aim aim) {
        if (player == null || target == null || aim == null || player == target
                || !player.isAlive() || player.isSpectator() || !target.canBeHitByProjectile()
                || target.isRemoved() || target.isSpectator() || target.level() != player.level()
                || player.isPassengerOfSameVehicle(target) || !valid(aim)) return false;
        ProjectileLaunch.Shot shot = aim.shot();
        if (!TrajectoryWorld.accepts(player, target, shot)) return false;
        AABB targetBounds = target.getBoundingBox();
        if (!validBounds(targetBounds) || !targetBounds.inflate(HIT_TOLERANCE).contains(aim.targetPoint())) return false;
        AABB futureTarget = targetBounds.move(ProjectileAim.predictedTarget(Vec3.ZERO, aim.targetVelocity(), aim.timeTicks()));
        if (!futureTarget.inflate(HIT_TOLERANCE).contains(aim.path().getLast())) return false;

        TrajectoryWorld world = new TrajectoryWorld(player, 1F);
        AABB pathBounds = new AABB(aim.path().getFirst(), aim.path().getLast());
        double distance = 0;
        for (int index = 1; index < aim.path().size(); index++) {
            Vec3 from = aim.path().get(index - 1), to = aim.path().get(index);
            distance += from.distanceTo(to);
            if (distance > ProjectileAim.MAX_RANGE + 1.0E-4) return false;
            pathBounds = pathBounds.minmax(new AABB(from, to));
            // Water queries include the projectile's body. Verify those neighbour chunks too before
            // either the volume query or block raycast, including at chunk-border launch positions.
            AABB swept = mediumBounds(from, to, shot);
            if (!world.loaded(swept.getMinPosition(), swept.getMaxPosition())
                    || !world.loaded(from, to)
                    || world.water(from, shot) || world.water(to, shot)) return false;
            // WATER also catches a water block crossed between tick nodes; air-only interception is
            // deliberately rejected rather than pretending its launch drag still applies there.
            if (player.level().clipIncludingBorder(new ClipContext(from, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, CollisionContext.empty()))
                    .getType() != HitResult.Type.MISS) return false;
        }

        List<Obstacle> obstacles = new ArrayList<>();
        AABB search = pathBounds.inflate(ProjectileAim.MAX_PREDICTION_DISTANCE + 1);
        for (Entity entity : player.level().getEntities(player, search,
                candidate -> candidate != target && TrajectoryWorld.accepts(player, candidate, shot))) {
            if (obstacles.size() >= MAX_OBSTACLES || !validBounds(entity.getBoundingBox())) return false;
            Vec3 velocity = entity.position().subtract(new Vec3(entity.xo, entity.yo, entity.zo));
            if (!ProjectileTrajectories.finite(velocity)) return false;
            obstacles.add(new Obstacle(entity.getBoundingBox(), velocity));
        }
        for (int index = 1; index < aim.path().size(); index++) {
            Vec3 from = aim.path().get(index - 1), to = aim.path().get(index);
            double startTime = index - 1;
            double endTime = Math.min(index, aim.timeTicks());
            // Expand conservatively for both newborn entity hit margins and the projectile body.
            double margin = Math.max(TrajectoryWorld.entityMargin(index - 1), shot.width() * 0.5);
            for (Obstacle obstacle : obstacles) {
                if (TrajectoryWorld.segmentBox(obstacle.bounds().inflate(margin), from, to) != null
                        || intersectsMovingBox(obstacle.bounds(), obstacle.velocity(), from, to,
                            startTime, endTime, margin)) return false;
            }
        }
        return true;
    }

    /** A translating entity is tested in its own moving coordinates, preserving each segment's time. */
    static boolean intersectsMovingBox(AABB bounds, Vec3 velocity, Vec3 from, Vec3 to,
                                       double startTime, double endTime, double margin) {
        if (!validBounds(bounds) || !ProjectileTrajectories.finite(velocity)
                || !ProjectileTrajectories.finite(from) || !ProjectileTrajectories.finite(to)
                || !Double.isFinite(startTime) || !Double.isFinite(endTime)
                || startTime < 0 || endTime < startTime || endTime > ProjectileAim.MAX_TICKS
                || !Double.isFinite(margin) || margin < 0 || margin > 4) return true;
        Vec3 start = from.subtract(ProjectileAim.predictedTarget(Vec3.ZERO, velocity, startTime));
        Vec3 end = to.subtract(ProjectileAim.predictedTarget(Vec3.ZERO, velocity, endTime));
        return TrajectoryWorld.segmentBox(bounds.inflate(margin), start, end) != null;
    }

    private static AABB mediumBounds(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot) {
        double half = shot.width() * 0.5;
        double down = shot.physics() == ProjectileLaunch.Physics.WIND_CHARGE ? 0.15F : 0;
        return new AABB(Math.min(from.x, to.x) - half, Math.min(from.y, to.y) - down,
                Math.min(from.z, to.z) - half, Math.max(from.x, to.x) + half,
                Math.max(from.y, to.y) - down + shot.height(), Math.max(from.z, to.z) + half);
    }

    private static boolean valid(ProjectileAim.Aim aim) {
        ProjectileLaunch.Shot shot = aim.shot();
        if (shot == null || shot.physics() == null || !ProjectileTrajectories.finite(shot.origin())
                || !Float.isFinite(shot.width()) || shot.width() <= 0.002F || shot.width() > 4
                || !Float.isFinite(shot.height()) || shot.height() <= 0.002F || shot.height() > 4
                || !Double.isFinite(aim.timeTicks()) || aim.timeTicks() <= 0 || aim.timeTicks() > ProjectileAim.MAX_TICKS
                || !ProjectileTrajectories.finite(aim.targetPoint()) || !ProjectileTrajectories.finite(aim.targetVelocity())
                || !ProjectileTrajectories.finite(aim.hitPoint()) || aim.path() == null
                || aim.path().size() != (int) Math.ceil(aim.timeTicks()) + 1) return false;
        for (Vec3 point : aim.path()) if (!ProjectileTrajectories.finite(point)) return false;
        return aim.path().getFirst().distanceToSqr(shot.origin()) <= 1.0E-8
                && aim.path().getLast().distanceToSqr(aim.hitPoint()) <= HIT_TOLERANCE * HIT_TOLERANCE;
    }

    private static boolean validBounds(AABB bounds) {
        return bounds != null && Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY)
                && Double.isFinite(bounds.minZ) && Double.isFinite(bounds.maxX)
                && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ)
                && bounds.maxX >= bounds.minX && bounds.maxY >= bounds.minY && bounds.maxZ >= bounds.minZ;
    }
}
