package com.mentalfrostbyte.jello.util.game;

import java.util.function.BiFunction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/** A read-only world view, retained only for one held-item prediction. */
final class TrajectoryWorld implements ProjectileTrajectories.Environment {
    private static final double WORLD_LIMIT = 30_000_000;
    private static final double MAX_SEGMENT = 256;
    private static final double RAY_EPSILON = 1.0E-7;
    private final LocalPlayer player;
    private final Level level;
    private final float partialTicks;

    TrajectoryWorld(LocalPlayer player, float partialTicks) {
        this.player = player;
        this.level = player.level();
        this.partialTicks = Float.isFinite(partialTicks) ? Mth.clamp(partialTicks, 0F, 1F) : 1F;
    }

    @Override
    public boolean loaded(Vec3 from, Vec3 to) {
        if (!validPosition(from) || !validPosition(to)
                || Math.min(from.y, to.y) < this.level.getMinY() - 16
                || from.distanceToSqr(to) > MAX_SEGMENT * MAX_SEGMENT) {
            return false;
        }
        // Block ray traversal extends both endpoints slightly; include its boundary-neighbour chunks.
        double marginX = (Math.abs(to.x - from.x) + 1) * RAY_EPSILON;
        double marginZ = (Math.abs(to.z - from.z) + 1) * RAY_EPSILON;
        return loadedArea(Math.min(from.x, to.x) - marginX, Math.min(from.z, to.z) - marginZ,
                Math.max(from.x, to.x) + marginX, Math.max(from.z, to.z) + marginZ);
    }

    private boolean loadedArea(double minX, double minZ, double maxX, double maxZ) {
        int firstX = Mth.floor(minX) >> 4;
        int firstZ = Mth.floor(minZ) >> 4;
        int lastX = Mth.floor(maxX) >> 4;
        int lastZ = Mth.floor(maxZ) >> 4;
        for (int x = firstX; x <= lastX; x++) {
            for (int z = firstZ; z <= lastZ; z++) {
                // ClientLevel.hasChunk is always true; the source checks actual storage without loading.
                if (!this.level.getChunkSource().hasChunk(x, z)) return false;
            }
        }
        return true;
    }

    @Override
    public boolean water(Vec3 position, ProjectileLaunch.Shot shot) {
        if (!validPosition(position) || !Float.isFinite(shot.width()) || !Float.isFinite(shot.height())
                || shot.width() <= 0.002F || shot.height() <= 0.002F
                || shot.width() > 16F || shot.height() > 16F) return false;
        double half = shot.width() / 2.0;
        double bottom = position.y - (shot.physics() == ProjectileLaunch.Physics.WIND_CHARGE ? 0.15F : 0);
        AABB box = new AABB(position.x - half, bottom, position.z - half,
                position.x + half, bottom + shot.height(), position.z + half).deflate(0.001);
        if (!loadedArea(box.minX, box.minZ, box.maxX, box.maxZ)) return false;
        int firstY = Math.max(Mth.floor(box.minY), this.level.getMinY());
        int lastY = Math.min(Mth.ceil(box.maxY) - 1, this.level.getMaxY());
        BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(box.minX); x < Mth.ceil(box.maxX); x++) {
            for (int y = firstY; y <= lastY; y++) {
                for (int z = Mth.floor(box.minZ); z < Mth.ceil(box.maxZ); z++) {
                    block.set(x, y, z);
                    FluidState fluid = this.level.getFluidState(block);
                    if (fluid.is(FluidTags.WATER)) {
                        double top = y + fluid.getHeight(this.level, block);
                        if (top >= box.minY && top > bottom) return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public ProjectileTrajectories.@Nullable Hit collision(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
        BlockHitResult block = this.level.clipIncludingBorder(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        boolean blockHit = block.getType() != HitResult.Type.MISS;
        Vec3 end = blockHit ? block.getLocation() : to;
        double nearest = blockHit ? from.distanceToSqr(end) : Double.POSITIVE_INFINITY;
        ProjectileTrajectories.Hit hit = blockHit
                ? new ProjectileTrajectories.Hit(end, block.getDirection(), false) : null;
        float margin = entityMargin(tick);
        AABB area = new AABB(from, end).inflate(1.0);
        for (Entity entity : this.level.getEntities(this.player, area, candidate -> accepts(this.player, candidate, shot))) {
            // Freeze each target at its currently rendered pose; do not predict its movement or bounces/piercing.
            Vec3 offset = entity.getPosition(this.partialTicks).subtract(entity.position());
            AABB bounds = entity.getBoundingBox().move(offset);
            Vec3 location = shot.physics() == ProjectileLaunch.Physics.ARROW
                    ? arrowIntersection(bounds, from, end, margin, this::unobstructedEnd)
                    : segmentBox(bounds.inflate(margin), from, end);
            if (location == null) continue;
            double distance = from.distanceToSqr(location);
            // At identical depth the block wins, preventing a target behind a wall taking precedence.
            if (distance < nearest) {
                nearest = distance;
                hit = new ProjectileTrajectories.Hit(location, null, true);
            }
        }
        return hit;
    }

    private @Nullable Vec3 unobstructedEnd(Vec3 from, Vec3 to) {
        if (!loaded(from, to)) return null;
        BlockHitResult block = this.level.clipIncludingBorder(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return block.getType() == HitResult.Type.MISS ? to : block.getLocation();
    }

    static @Nullable Vec3 arrowIntersection(AABB bounds, Vec3 from, Vec3 to, float margin,
                                           BiFunction<Vec3, Vec3, @Nullable Vec3> unobstructedEnd) {
        Vec3 exact = segmentBox(bounds, from, to);
        if (exact != null || margin <= 0) return exact;
        Vec3 outside = segmentBox(bounds.inflate(margin), from, to);
        if (outside == null) return null;
        // ProjectileUtil's arrow path confirms that an expanded-box graze can reach the real surface.
        // The extra block clip rejects a margin protruding through the corner of a wall.
        Vec3 end = unobstructedEnd.apply(outside, bounds.getCenter());
        return end == null ? null : segmentBox(bounds, outside, end);
    }

    static boolean accepts(LocalPlayer player, Entity entity, ProjectileLaunch.Shot shot) {
        if (entity == player || entity.isRemoved() || entity.isSpectator()
                || !entity.canBeHitByProjectile() || player.isPassengerOfSameVehicle(entity)) return false;
        if (shot.physics() == ProjectileLaunch.Physics.ARROW && entity instanceof Player other
                && !player.canHarmPlayer(other)) return false;
        // RemotePlayer sets noPhysics for client interpolation; its server player still collides.
        // Spectators were excluded above, while genuinely non-physical non-player entities stay out.
        return shot.physics() != ProjectileLaunch.Physics.WIND_CHARGE
                || ((!entity.noPhysics || entity instanceof RemotePlayer)
                    && !(entity instanceof AbstractWindCharge) && !(entity instanceof EndCrystal));
    }

    static float entityMargin(int tick) {
        // ProjectileUtil.computeMargin after the tick increments the newborn projectile's age.
        return Math.max(0F, Math.min(0.3F, (tick - 1F) / 20F));
    }

    static @Nullable Vec3 segmentBox(AABB box, Vec3 from, Vec3 to) {
        if (!validPosition(from) || !validPosition(to)) return null;
        double start = 0;
        double end = 1;
        for (int axis = 0; axis < 3; axis++) {
            double origin = axis == 0 ? from.x : axis == 1 ? from.y : from.z;
            double destination = axis == 0 ? to.x : axis == 1 ? to.y : to.z;
            double min = axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
            double max = axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
            if (!Double.isFinite(min) || !Double.isFinite(max)) return null;
            double delta = destination - origin;
            if (delta == 0) {
                if (origin < min || origin > max) return null;
            } else {
                double near = (min - origin) / delta;
                double far = (max - origin) / delta;
                start = Math.max(start, Math.min(near, far));
                end = Math.min(end, Math.max(near, far));
                if (start > end) return null;
            }
        }
        // Starting inside is an immediate hit, instead of reporting the exit face of that entity.
        return from.lerp(to, start);
    }

    private static boolean validPosition(Vec3 position) {
        return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                && Math.abs(position.x) < WORLD_LIMIT && Math.abs(position.y) < WORLD_LIMIT
                && Math.abs(position.z) < WORLD_LIMIT;
    }
}
