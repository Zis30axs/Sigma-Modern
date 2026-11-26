package com.mentalfrostbyte.jello.util.math;

import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Visible-aperture solver for aiming through stairs, slabs, trapdoors, fences and elevation ledges.
 *
 * <p>Uses a three-tier cascade on the game thread with zero steady-state heap allocations:
 * <ol>
 *   <li>Corridor {@link ClipContext.Block#OUTLINE} extraction via {@link VoxelShape#forAllBoxes}: when the corridor
 *       contains only air ({@code boxCount == 0}) or when the sticky/preferred anchor clears a 3D safety cross,
 *       returns in {@code O(1)} without scanning a grid.</li>
 *   <li>Sub-1/16 block ({@code 0.04m}) visible-face slab rasterization and a two-pass 2D Manhattan clearance transform
 *       with a capped safety margin, falling back from {@code HEAD}/{@code BODY}/{@code FEET} to any open aperture on
 *       the hitbox when the preferred band is blocked or beyond reach.</li>
 *   <li>A {@code 2x2} integer mouse-step (GCD) neighbourhood check so discrete sensitivity rounding never clips the
 *       lip of a narrow gap when an adjacent whole mouse step clears it.</li>
 * </ol>
 */
public final class HitboxAperture {

    private static final double EDGE_INSET = 0.012;
    private static final double FAST_MARGIN = 0.12;
    private static final double CELL_STEP = 0.04;
    private static final int MAX_GRID_U = 33;
    private static final int MAX_GRID_V = 49;
    private static final int CLEARANCE_CAP = 3;
    private static final int CLEARANCE_TIE_CAP = 8;
    private static final double ENTRY_EPSILON = 9.9E-4;
    public static final double EXIT_EPSILON = 1.0E-5;

    /** Vertical preference band on an entity's hitbox; falls back to the full hitbox if blocked or out of reach. */
    public enum Zone {
        BEST(0.02, 0.98, -1.0),
        HEAD(0.68, 0.98, 0.85),
        BODY(0.32, 0.68, 0.50),
        FEET(0.02, 0.32, 0.15);

        private final double minFraction;
        private final double maxFraction;
        private final double anchorFraction;

        Zone(final double minFraction, final double maxFraction, final double anchorFraction) {
            this.minFraction = minFraction;
            this.maxFraction = maxFraction;
            this.anchorFraction = anchorFraction;
        }

        public boolean inBand(final double y, final AABB box) {
            if (this == BEST) {
                return true;
            }
            double low = Mth.lerp(this.minFraction, box.minY, box.maxY) - 1.0E-4;
            double high = Mth.lerp(this.maxFraction, box.minY, box.maxY) + 1.0E-4;
            return y >= low && y <= high;
        }

        public double preferredY(final Vec3 eye, final AABB shrunkBox, final AABB fullBox) {
            if (this == BEST) {
                return Mth.clamp(eye.y, shrunkBox.minY, shrunkBox.maxY);
            }
            double raw = Mth.lerp(this.anchorFraction, fullBox.minY, fullBox.maxY);
            return Mth.clamp(raw, shrunkBox.minY, shrunkBox.maxY);
        }

        public Vec3 preferredAnchor(final Vec3 eye, final AABB fullBox) {
            AABB shrunk = shrinkSafe(fullBox, EDGE_INSET);
            double x = Mth.clamp(eye.x, shrunk.minX, shrunk.maxX);
            double y = this.preferredY(eye, shrunk, fullBox);
            double z = Mth.clamp(eye.z, shrunk.minZ, shrunk.maxZ);
            if (Math.abs(x - eye.x) < 1.0E-3 && Math.abs(z - eye.z) < 1.0E-3) {
                Vec3 center = fullBox.getCenter();
                return new Vec3(center.x, y, center.z);
            }
            return new Vec3(x, y, z);
        }
    }

    /** Chosen world aim point together with its center-relative offset for next-tick hysteresis. */
    public record Selection(Vec3 point, Vec3 offsetFromCenter, boolean inPreferredZone) {
    }

    private HitboxAperture() {
    }

    public static AABB shrinkSafe(final AABB box, final double inset) {
        double maxInset = Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) * 0.25;
        double safe = Math.max(0.0, Math.min(inset, maxInset));
        return box.deflate(safe);
    }

    /**
     * If {@code quantized} is within a single mouse step of {@code idealContinuous} yet misses the target gap because
     * {@code Math.round} stepped onto an adjacent stair/slab edge, checks the surrounding {@code 2x2} floor/ceil
     * whole-mouse-step lattice and picks the nearest step that passes {@code canHit}.
     */
    public static Rotation refineQuantized(final Rotation from, final Rotation idealContinuous, final Rotation quantized,
                                           final double mouseStep, final Predicate<Rotation> canHit) {
        if (mouseStep <= 0.0 || canHit.test(quantized)) {
            return quantized;
        }
        double limit = mouseStep + 1.0E-4;
        if (Math.abs(idealContinuous.yaw() - quantized.yaw()) > limit
                || Math.abs(idealContinuous.pitch() - quantized.pitch()) > limit) {
            return quantized;
        }

        double dyaw = idealContinuous.yaw() - from.yaw();
        double dpitch = idealContinuous.pitch() - from.pitch();
        double yawFloor = Math.floor(dyaw / mouseStep) * mouseStep;
        double yawCeil = Math.ceil(dyaw / mouseStep) * mouseStep;
        double pitchFloor = Math.floor(dpitch / mouseStep) * mouseStep;
        double pitchCeil = Math.ceil(dpitch / mouseStep) * mouseStep;

        double[] yawSteps = {yawFloor, yawCeil};
        double[] pitchSteps = {pitchFloor, pitchCeil};

        Rotation best = quantized;
        double bestErrSq = Double.POSITIVE_INFINITY;
        for (double yStep : yawSteps) {
            float candidateYaw = (float) (from.yaw() + yStep);
            for (double pStep : pitchSteps) {
                float candidatePitch = Mth.clamp((float) (from.pitch() + pStep), -90.0F, 90.0F);
                Rotation candidate = new Rotation(candidateYaw, candidatePitch);
                double errYaw = candidateYaw - idealContinuous.yaw();
                double errPitch = candidatePitch - idealContinuous.pitch();
                double errSq = errYaw * errYaw + errPitch * errPitch;
                if (errSq < bestErrSq && canHit.test(candidate)) {
                    bestErrSq = errSq;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * Reusable single-threaded workspace for world obstacle extraction and surface-aperture scanning.
     */
    public static final class Scanner {

        private double[] boxes = new double[6 * 128];
        private int boxCount;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        private double currentBlockX;
        private double currentBlockY;
        private double currentBlockZ;
        private double corridorMinX;
        private double corridorMinY;
        private double corridorMinZ;
        private double corridorMaxX;
        private double corridorMaxY;
        private double corridorMaxZ;

        private final Shapes.DoubleLineConsumer boxConsumer = (x1, y1, z1, x2, y2, z2) -> {
            double wx1 = this.currentBlockX + x1;
            double wy1 = this.currentBlockY + y1;
            double wz1 = this.currentBlockZ + z1;
            double wx2 = this.currentBlockX + x2;
            double wy2 = this.currentBlockY + y2;
            double wz2 = this.currentBlockZ + z2;
            if (wx2 >= this.corridorMinX && wx1 <= this.corridorMaxX
                    && wy2 >= this.corridorMinY && wy1 <= this.corridorMaxY
                    && wz2 >= this.corridorMinZ && wz1 <= this.corridorMaxZ) {
                this.addObstacle(wx1, wy1, wz1, wx2, wy2, wz2);
            }
        };

        private final boolean[] gridVisible = new boolean[MAX_GRID_U * MAX_GRID_V];
        private final int[] gridClearance = new int[MAX_GRID_U * MAX_GRID_V];
        private final double[] gridX = new double[MAX_GRID_U * MAX_GRID_V];
        private final double[] gridY = new double[MAX_GRID_U * MAX_GRID_V];
        private final double[] gridZ = new double[MAX_GRID_U * MAX_GRID_V];

        private double bestZoneX;
        private double bestZoneY;
        private double bestZoneZ;
        private double bestZoneScore;

        private double bestAnyX;
        private double bestAnyY;
        private double bestAnyZ;
        private double bestAnyScore;

        public void clearObstacles() {
            this.boxCount = 0;
        }

        public int obstacleCount() {
            return this.boxCount;
        }

        public void addObstacle(final AABB box) {
            this.addObstacle(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }

        public void addObstacle(final double minX, final double minY, final double minZ,
                                final double maxX, final double maxY, final double maxZ) {
            int offset = this.boxCount * 6;
            if (offset + 6 > this.boxes.length) {
                this.boxes = Arrays.copyOf(this.boxes, this.boxes.length * 2);
            }
            this.boxes[offset] = minX;
            this.boxes[offset + 1] = minY;
            this.boxes[offset + 2] = minZ;
            this.boxes[offset + 3] = maxX;
            this.boxes[offset + 4] = maxY;
            this.boxes[offset + 5] = maxZ;
            this.boxCount++;
        }

        /** Extracts {@link ClipContext.Block#OUTLINE} sub-boxes inside the corridor between {@code eye} and {@code targetBox}. */
        public void collectWorldObstacles(final ClientLevel level, final LocalPlayer player, final Vec3 eye,
                                          final AABB targetBox, final double maxRange) {
            this.clearObstacles();
            double reachBound = maxRange + 0.25;
            this.corridorMinX = Math.max(eye.x - reachBound, Math.min(eye.x, targetBox.minX) - 1.0E-3);
            this.corridorMinY = Math.max(eye.y - reachBound, Math.min(eye.y, targetBox.minY) - 1.0E-3);
            this.corridorMinZ = Math.max(eye.z - reachBound, Math.min(eye.z, targetBox.minZ) - 1.0E-3);
            this.corridorMaxX = Math.min(eye.x + reachBound, Math.max(eye.x, targetBox.maxX) + 1.0E-3);
            this.corridorMaxY = Math.min(eye.y + reachBound, Math.max(eye.y, targetBox.maxY) + 1.0E-3);
            this.corridorMaxZ = Math.min(eye.z + reachBound, Math.max(eye.z, targetBox.maxZ) + 1.0E-3);

            int minBx = Mth.floor(this.corridorMinX);
            int minBy = Mth.floor(this.corridorMinY);
            int minBz = Mth.floor(this.corridorMinZ);
            int maxBx = Mth.floor(this.corridorMaxX);
            int maxBy = Mth.floor(this.corridorMaxY);
            int maxBz = Mth.floor(this.corridorMaxZ);

            CollisionContext context = CollisionContext.of(player);
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int by = minBy; by <= maxBy; by++) {
                    for (int bz = minBz; bz <= maxBz; bz++) {
                        this.cursor.set(bx, by, bz);
                        if (!level.isInWorldBounds(this.cursor)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(this.cursor);
                        if (state.isAir()) {
                            continue;
                        }
                        VoxelShape shape = ClipContext.Block.OUTLINE.get(state, level, this.cursor, context);
                        if (shape.isEmpty()) {
                            continue;
                        }
                        this.currentBlockX = bx;
                        this.currentBlockY = by;
                        this.currentBlockZ = bz;
                        shape.forAllBoxes(this.boxConsumer);
                    }
                }
            }
        }

        /** True when at least one unobstructed point on {@code entity}'s hitbox lies within {@code aimRange}. */
        public boolean hasVisibleAperture(final ClientLevel level, final LocalPlayer player, final Entity entity,
                                          final Vec3 eye, final double range, final double aimRange) {
            AABB targetBox = entity.getBoundingBox().inflate(entity.getPickRadius());
            double maxReach = Math.max(range, aimRange);
            if (targetBox.distanceToSqr(eye) > maxReach * maxReach) {
                return false;
            }
            if (targetBox.contains(eye)) {
                return true;
            }
            this.collectWorldObstacles(level, player, eye, targetBox, maxReach);
            if (this.boxCount == 0) {
                return true;
            }
            Vec3 quick = Zone.BEST.preferredAnchor(eye, targetBox);
            if (this.verifiesLookRay(eye, targetBox, quick.x, quick.y, quick.z, maxReach)) {
                return true;
            }
            Rotation neutral = Rotations.towards(eye, targetBox.getCenter());
            return this.selectFromObstacles(eye, neutral, targetBox, range, maxReach, Zone.BEST, null, true, false) != null;
        }

        /**
         * Picks the optimal aim point on {@code entity} in {@code level}, resolving open corridors in {@code O(1)} and
         * scanning visible hitbox surfaces when obstacles are nearby.
         */
        public @Nullable Selection selectInWorld(final ClientLevel level, final LocalPlayer player, final Entity entity,
                                                 final Vec3 eye, final Rotation from, final double range,
                                                 final double aimRange, final Zone zone,
                                                 final @Nullable Vec3 previousOffset,
                                                 final boolean previousInPreferredZone,
                                                 final boolean ignoreBlocks) {
            AABB targetBox = entity.getBoundingBox().inflate(entity.getPickRadius());
            double maxReach = Math.max(range, aimRange);
            if (targetBox.distanceToSqr(eye) > maxReach * maxReach) {
                return null;
            }
            if (!ignoreBlocks && !targetBox.contains(eye)) {
                this.collectWorldObstacles(level, player, eye, targetBox, maxReach);
            } else {
                this.clearObstacles();
            }
            return this.selectFromObstacles(eye, from, targetBox, range, maxReach, zone,
                    previousOffset, previousInPreferredZone, ignoreBlocks);
        }

        /** Overload defaulting {@code previousInPreferredZone} to {@code true} when {@code previousOffset} is present. */
        public @Nullable Selection selectFromObstacles(final Vec3 eye, final Rotation from, final AABB targetBox,
                                                       final double range, final double aimRange, final Zone zone,
                                                       final @Nullable Vec3 previousOffset, final boolean ignoreBlocks) {
            return this.selectFromObstacles(eye, from, targetBox, range, aimRange, zone,
                    previousOffset, previousOffset != null, ignoreBlocks);
        }

        /**
         * Pure-geometry solver over the obstacles currently loaded in this {@link Scanner}; directly testable without
         * a {@link ClientLevel}.
         */
        public @Nullable Selection selectFromObstacles(final Vec3 eye, final Rotation from, final AABB targetBox,
                                                       final double range, final double aimRange, final Zone zone,
                                                       final @Nullable Vec3 previousOffset,
                                                       final boolean previousInPreferredZone,
                                                       final boolean ignoreBlocks) {
            double maxReach = Math.max(range, aimRange);
            double minDistSq = targetBox.distanceToSqr(eye);
            if (minDistSq > maxReach * maxReach) {
                return null;
            }
            Vec3 center = targetBox.getCenter();
            if (targetBox.contains(eye)) {
                Vec3 inside = zone.preferredAnchor(eye, targetBox);
                return new Selection(inside, inside.subtract(center), true);
            }

            double primaryRange = minDistSq <= range * range ? range : maxReach;
            if (ignoreBlocks || this.boxCount == 0) {
                return selectUnobstructed(eye, targetBox, primaryRange, maxReach, zone, previousOffset, previousInPreferredZone);
            }

            // Tier 1: O(1) 3D safety-cross fast path against pre-extracted corridor boxes.
            AABB safeBox = shrinkSafe(targetBox, Math.max(EDGE_INSET, 0.03));
            if (previousOffset != null && previousInPreferredZone) {
                Vec3 sticky = clampToBox(center.add(previousOffset), safeBox);
                if (zone.inBand(sticky.y, targetBox)
                        && this.hasClearCrossMargin(eye, targetBox, safeBox, sticky, primaryRange)) {
                    return new Selection(sticky, sticky.subtract(center), true);
                }
            }
            Vec3 anchor = zone.preferredAnchor(eye, targetBox);
            if (this.hasClearCrossMargin(eye, targetBox, safeBox, anchor, primaryRange)) {
                return new Selection(anchor, anchor.subtract(center), true);
            }

            // Tier 2: Try attack range first whenever any part of the hitbox could be within attack reach; else pre-aim.
            if (minDistSq <= range * range) {
                Selection withinAttackRange = this.scanFaces(eye, from, targetBox, range, zone, previousOffset);
                if (withinAttackRange != null) {
                    return withinAttackRange;
                }
            }
            if (maxReach > range) {
                return this.scanFaces(eye, from, targetBox, maxReach, zone, previousOffset);
            }
            return null;
        }

        private boolean hasClearCrossMargin(final Vec3 eye, final AABB targetBox, final AABB safeBox,
                                            final Vec3 point, final double effectiveRange) {
            if (!this.verifiesLookRay(eye, targetBox, point.x, point.y, point.z, effectiveRange)) {
                return false;
            }
            double rangeSq = effectiveRange * effectiveRange;
            double yMargin = Math.min(FAST_MARGIN, safeBox.getYsize() * 0.25);
            if (yMargin > 1.0E-3) {
                double yLow = Math.max(safeBox.minY, point.y - yMargin);
                double yHigh = Math.min(safeBox.maxY, point.y + yMargin);
                if (!this.isSlabRayClear(eye, targetBox, point.x, yLow, point.z, rangeSq)
                        || !this.isSlabRayClear(eye, targetBox, point.x, yHigh, point.z, rangeSq)) {
                    return false;
                }
            }
            double dx = point.x - eye.x;
            double dz = point.z - eye.z;
            double horiz = Math.hypot(dx, dz);
            if (horiz > 1.0E-3) {
                double hMargin = Math.min(FAST_MARGIN, Math.min(safeBox.getXsize(), safeBox.getZsize()) * 0.35);
                double px = -dz / horiz * hMargin;
                double pz = dx / horiz * hMargin;
                double lx = Mth.clamp(point.x - px, safeBox.minX, safeBox.maxX);
                double lz = Mth.clamp(point.z - pz, safeBox.minZ, safeBox.maxZ);
                double rx = Mth.clamp(point.x + px, safeBox.minX, safeBox.maxX);
                double rz = Mth.clamp(point.z + pz, safeBox.minZ, safeBox.maxZ);
                if (!this.isSlabRayClear(eye, targetBox, lx, point.y, lz, rangeSq)
                        || !this.isSlabRayClear(eye, targetBox, rx, point.y, rz, rangeSq)) {
                    return false;
                }
            }
            return true;
        }

        private @Nullable Selection scanFaces(final Vec3 eye, final Rotation from, final AABB targetBox,
                                              final double effectiveRange, final Zone zone,
                                              final @Nullable Vec3 previousOffset) {
            AABB shrunk = shrinkSafe(targetBox, EDGE_INSET);
            double prefY = zone.preferredY(eye, shrunk, targetBox);
            Vec3 center = targetBox.getCenter();
            boolean hasPrev = previousOffset != null;
            double prevX = hasPrev ? center.x + previousOffset.x : 0.0;
            double prevY = hasPrev ? center.y + previousOffset.y : 0.0;
            double prevZ = hasPrev ? center.z + previousOffset.z : 0.0;

            this.bestZoneScore = Double.NEGATIVE_INFINITY;
            this.bestAnyScore = Double.NEGATIVE_INFINITY;

            // Scan the 1 to 3 faces of shrunkBox facing the eye.
            if (eye.x < shrunk.minX) {
                this.evaluateFace(0, shrunk.minX, shrunk.minZ, shrunk.maxZ, shrunk.minY, shrunk.maxY,
                        eye, from, targetBox, effectiveRange, zone, prefY, hasPrev, prevX, prevY, prevZ);
            } else if (eye.x > shrunk.maxX) {
                this.evaluateFace(0, shrunk.maxX, shrunk.minZ, shrunk.maxZ, shrunk.minY, shrunk.maxY,
                        eye, from, targetBox, effectiveRange, zone, prefY, hasPrev, prevX, prevY, prevZ);
            }

            if (eye.z < shrunk.minZ) {
                this.evaluateFace(2, shrunk.minZ, shrunk.minX, shrunk.maxX, shrunk.minY, shrunk.maxY,
                        eye, from, targetBox, effectiveRange, zone, prefY, hasPrev, prevX, prevY, prevZ);
            } else if (eye.z > shrunk.maxZ) {
                this.evaluateFace(2, shrunk.maxZ, shrunk.minX, shrunk.maxX, shrunk.minY, shrunk.maxY,
                        eye, from, targetBox, effectiveRange, zone, prefY, hasPrev, prevX, prevY, prevZ);
            }

            if (eye.y < shrunk.minY) {
                this.evaluateFace(1, shrunk.minY, shrunk.minX, shrunk.maxX, shrunk.minZ, shrunk.maxZ,
                        eye, from, targetBox, effectiveRange, zone, prefY, hasPrev, prevX, prevY, prevZ);
            } else if (eye.y > shrunk.maxY) {
                this.evaluateFace(1, shrunk.maxY, shrunk.minX, shrunk.maxX, shrunk.minZ, shrunk.maxZ,
                        eye, from, targetBox, effectiveRange, zone, prefY, hasPrev, prevX, prevY, prevZ);
            }

            if (this.bestZoneScore > Double.NEGATIVE_INFINITY) {
                Vec3 chosen = new Vec3(this.bestZoneX, this.bestZoneY, this.bestZoneZ);
                return new Selection(chosen, chosen.subtract(center), true);
            }
            if (this.bestAnyScore > Double.NEGATIVE_INFINITY) {
                Vec3 chosen = new Vec3(this.bestAnyX, this.bestAnyY, this.bestAnyZ);
                return new Selection(chosen, chosen.subtract(center), false);
            }
            return null;
        }

        private void evaluateFace(final int fixedAxis, final double fixedCoord,
                                  final double minU, final double maxU,
                                  final double minV, final double maxV,
                                  final Vec3 eye, final Rotation from, final AABB targetBox,
                                  final double effectiveRange, final Zone zone, final double prefY,
                                  final boolean hasPrev, final double prevX, final double prevY, final double prevZ) {
            int nu = Mth.clamp((int) Math.ceil((maxU - minU) / CELL_STEP) + 1, 3, MAX_GRID_U);
            int nv = Mth.clamp((int) Math.ceil((maxV - minV) / CELL_STEP) + 1, 3, MAX_GRID_V);
            double rangeSq = effectiveRange * effectiveRange;

            int anyVisible = 0;
            for (int iv = 0; iv < nv; iv++) {
                double tv = nv == 1 ? 0.5 : (double) iv / (nv - 1);
                double v = Mth.lerp(tv, minV, maxV);
                int rowOffset = iv * nu;
                for (int iu = 0; iu < nu; iu++) {
                    double tu = nu == 1 ? 0.5 : (double) iu / (nu - 1);
                    double u = Mth.lerp(tu, minU, maxU);
                    double px;
                    double py;
                    double pz;
                    if (fixedAxis == 0) {
                        px = fixedCoord;
                        pz = u;
                        py = v;
                    } else if (fixedAxis == 2) {
                        px = u;
                        py = v;
                        pz = fixedCoord;
                    } else {
                        px = u;
                        py = fixedCoord;
                        pz = v;
                    }

                    int idx = rowOffset + iu;
                    this.gridX[idx] = px;
                    this.gridY[idx] = py;
                    this.gridZ[idx] = pz;
                    boolean clear = this.isSlabRayClear(eye, targetBox, px, py, pz, rangeSq);
                    this.gridVisible[idx] = clear;
                    if (clear) {
                        anyVisible++;
                    }
                }
            }

            if (anyVisible == 0) {
                return;
            }

            computeClearance(this.gridVisible, this.gridClearance, nu, nv);

            for (int iv = 0; iv < nv; iv++) {
                int rowOffset = iv * nu;
                for (int iu = 0; iu < nu; iu++) {
                    int idx = rowOffset + iu;
                    if (!this.gridVisible[idx]) {
                        continue;
                    }
                    double px = this.gridX[idx];
                    double py = this.gridY[idx];
                    double pz = this.gridZ[idx];
                    int clearance = this.gridClearance[idx];

                    int cappedClearance = Math.min(clearance, CLEARANCE_CAP);
                    int tieClearance = Math.min(clearance, CLEARANCE_TIE_CAP);
                    double heightErr = zone == Zone.BEST ? 0.0 : Math.abs(py - prefY);
                    double turnDeg = angularDistance(eye, from, px, py, pz);
                    double stickyDist = 0.0;
                    if (hasPrev) {
                        double sx = px - prevX;
                        double sy = py - prevY;
                        double sz = pz - prevZ;
                        stickyDist = Math.sqrt(sx * sx + sy * sy + sz * sz);
                    }

                    double score = cappedClearance * 100.0
                            + tieClearance * 0.5
                            - heightErr * 25.0
                            - turnDeg * 0.35
                            - stickyDist * 12.0;

                    if (zone.inBand(py, targetBox) && score > this.bestZoneScore
                            && this.verifiesLookRay(eye, targetBox, px, py, pz, effectiveRange)) {
                        this.bestZoneScore = score;
                        this.bestZoneX = px;
                        this.bestZoneY = py;
                        this.bestZoneZ = pz;
                    }

                    double fallbackScore = cappedClearance * 100.0
                            + tieClearance * 0.5
                            - (zone == Zone.BEST ? 0.0 : heightErr * 8.0)
                            - turnDeg * 0.35
                            - stickyDist * 12.0;
                    if (fallbackScore > this.bestAnyScore
                            && this.verifiesLookRay(eye, targetBox, px, py, pz, effectiveRange)) {
                        this.bestAnyScore = fallbackScore;
                        this.bestAnyX = px;
                        this.bestAnyY = py;
                        this.bestAnyZ = pz;
                    }
                }
            }
        }

        /** Checks that the float-rounded {@link Rotation} (through {@code Mth.sin/cos}) still hits cleanly. */
        public boolean verifiesLookRay(final Vec3 eye, final AABB targetBox,
                                       final double px, final double py, final double pz,
                                       final double effectiveRange) {
            Rotation rot = towardsCoords(eye, px, py, pz);
            return this.verifiesRotation(eye, rot, targetBox, effectiveRange);
        }

        /** Checks whether {@code rotation} reaches {@code targetBox} within {@code effectiveRange} without hitting obstacles. */
        public boolean verifiesRotation(final Vec3 eye, final Rotation rotation,
                                        final AABB targetBox, final double effectiveRange) {
            Optional<Vec3> hit = Rotations.hit(eye, rotation, targetBox, effectiveRange);
            if (hit.isEmpty()) {
                return false;
            }
            Vec3 loc = hit.get();
            return !this.isSegmentBlocked(eye.x, eye.y, eye.z, loc.x - eye.x, loc.y - eye.y, loc.z - eye.z);
        }

        /** True when a ray from {@code eye} to {@code (px, py, pz)} enters {@code targetBox} in range with no prior block hit. */
        public boolean isSlabRayClear(final Vec3 eye, final AABB targetBox,
                                      final double px, final double py, final double pz,
                                      final double maxRangeSq) {
            double dx = px - eye.x;
            double dy = py - eye.y;
            double dz = pz - eye.z;
            double lenSq = dx * dx + dy * dy + dz * dz;
            if (lenSq < 1.0E-12) {
                return true;
            }
            double tHit = entryParam(eye.x, eye.y, eye.z, dx, dy, dz,
                    targetBox.minX, targetBox.minY, targetBox.minZ,
                    targetBox.maxX, targetBox.maxY, targetBox.maxZ);
            if (tHit < 0.0) {
                return false;
            }
            if (tHit * tHit * lenSq > maxRangeSq) {
                return false;
            }
            if (tHit <= 1.0E-7) {
                return true;
            }
            return !this.isSegmentBlocked(eye.x, eye.y, eye.z, dx * tHit, dy * tHit, dz * tHit);
        }

        /** Tests segment {@code origin + s * delta} for {@code s in (ENTRY_EPSILON, 1 - EXIT_EPSILON)} against all obstacles. */
        public boolean isSegmentBlocked(final double ox, final double oy, final double oz,
                                        final double dx, final double dy, final double dz) {
            int limit = this.boxCount * 6;
            double[] data = this.boxes;
            for (int i = 0; i < limit; i += 6) {
                if (slabIntersects(ox, oy, oz, dx, dy, dz,
                        data[i], data[i + 1], data[i + 2],
                        data[i + 3], data[i + 4], data[i + 5])) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Selection selectUnobstructed(final Vec3 eye, final AABB targetBox,
                                                final double primaryRange, final double maxReach,
                                                final Zone zone, final @Nullable Vec3 previousOffset,
                                                final boolean previousInPreferredZone) {
        Vec3 center = targetBox.getCenter();
        AABB shrunk = shrinkSafe(targetBox, EDGE_INSET);
        if (previousOffset != null && previousInPreferredZone) {
            Vec3 sticky = clampToBox(center.add(previousOffset), shrunk);
            if (zone.inBand(sticky.y, targetBox) && hitsWithin(eye, targetBox, sticky, primaryRange)) {
                return new Selection(sticky, sticky.subtract(center), true);
            }
        }

        double bandMinY = zone == Zone.BEST ? shrunk.minY : Mth.clamp(Mth.lerp(zone.minFraction, targetBox.minY, targetBox.maxY), shrunk.minY, shrunk.maxY);
        double bandMaxY = zone == Zone.BEST ? shrunk.maxY : Mth.clamp(Mth.lerp(zone.maxFraction, targetBox.minY, targetBox.maxY), shrunk.minY, shrunk.maxY);
        Vec3 anchor = zone.preferredAnchor(eye, targetBox);
        if (hitsWithin(eye, targetBox, anchor, primaryRange)) {
            return new Selection(anchor, anchor.subtract(center), true);
        }
        Vec3 nearestInBand = new Vec3(
                Mth.clamp(eye.x, shrunk.minX, shrunk.maxX),
                Mth.clamp(eye.y, Math.min(bandMinY, bandMaxY), Math.max(bandMinY, bandMaxY)),
                Mth.clamp(eye.z, shrunk.minZ, shrunk.maxZ));
        if (Math.abs(nearestInBand.x - eye.x) < 1.0E-3 && Math.abs(nearestInBand.z - eye.z) < 1.0E-3) {
            nearestInBand = new Vec3(center.x, nearestInBand.y, center.z);
        }
        if (hitsWithin(eye, targetBox, nearestInBand, primaryRange)) {
            return new Selection(nearestInBand, nearestInBand.subtract(center), true);
        }

        // Soft fallback: elevation ledge where the preferred zone is beyond primaryRange, while nearest point reaches.
        Vec3 nearestOverall = Rotations.nearestPoint(eye, targetBox, EDGE_INSET);
        double allowRange = hitsWithin(eye, targetBox, nearestOverall, primaryRange) ? primaryRange : maxReach;
        boolean inZone = zone.inBand(nearestOverall.y, targetBox);
        if (!inZone && allowRange > primaryRange && hitsWithin(eye, targetBox, anchor, allowRange)) {
            return new Selection(anchor, anchor.subtract(center), true);
        }
        return new Selection(nearestOverall, nearestOverall.subtract(center), inZone);
    }

    private static boolean hitsWithin(final Vec3 eye, final AABB targetBox, final Vec3 aimPoint, final double range) {
        return Rotations.hit(eye, Rotations.towards(eye, aimPoint), targetBox, range).isPresent();
    }

    private static Vec3 clampToBox(final Vec3 point, final AABB box) {
        return new Vec3(
                Mth.clamp(point.x, box.minX, box.maxX),
                Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ));
    }

    /** Two-pass 2D Manhattan distance transform over {@code visible[nv * nu]}. */
    static void computeClearance(final boolean[] visible, final int[] clearance, final int nu, final int nv) {
        int inf = nu + nv + 1;
        for (int iv = 0; iv < nv; iv++) {
            int row = iv * nu;
            boolean borderV = iv == 0 || iv == nv - 1;
            for (int iu = 0; iu < nu; iu++) {
                int idx = row + iu;
                if (!visible[idx]) {
                    clearance[idx] = 0;
                } else if (borderV || iu == 0 || iu == nu - 1) {
                    clearance[idx] = 1;
                } else {
                    int top = clearance[(iv - 1) * nu + iu];
                    int left = clearance[row + iu - 1];
                    clearance[idx] = Math.min(inf, Math.min(top, left) + 1);
                }
            }
        }

        for (int iv = nv - 1; iv >= 0; iv--) {
            int row = iv * nu;
            for (int iu = nu - 1; iu >= 0; iu--) {
                int idx = row + iu;
                int c = clearance[idx];
                if (c <= 1) {
                    continue;
                }
                if (iv + 1 < nv) {
                    c = Math.min(c, clearance[(iv + 1) * nu + iu] + 1);
                }
                if (iu + 1 < nu) {
                    c = Math.min(c, clearance[row + iu + 1] + 1);
                }
                clearance[idx] = c;
            }
        }
    }

    private static Rotation towardsCoords(final Vec3 eye, final double px, final double py, final double pz) {
        double dx = px - eye.x;
        double dy = py - eye.y;
        double dz = pz - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new Rotation(yaw, Mth.clamp(pitch, -90.0F, 90.0F));
    }

    private static double angularDistance(final Vec3 eye, final Rotation from,
                                          final double px, final double py, final double pz) {
        Rotation rot = towardsCoords(eye, px, py, pz);
        double dyaw = Mth.wrapDegrees(rot.yaw() - from.yaw());
        double dpitch = rot.pitch() - from.pitch();
        return Math.hypot(dyaw, dpitch);
    }

    /** Parameter {@code t in [0, 1]} where {@code origin + t * delta} enters {@code box}, or {@code -1} on miss. */
    static double entryParam(final double ox, final double oy, final double oz,
                             final double dx, final double dy, final double dz,
                             final double minX, final double minY, final double minZ,
                             final double maxX, final double maxY, final double maxZ) {
        if (ox >= minX && ox <= maxX && oy >= minY && oy <= maxY && oz >= minZ && oz <= maxZ) {
            return 0.0;
        }
        double tEnter = 0.0;
        double tExit = 1.0;

        if (Math.abs(dx) < 1.0E-9) {
            if (ox < minX - 1.0E-7 || ox > maxX + 1.0E-7) return -1.0;
        } else {
            double inv = 1.0 / dx;
            double t1 = (minX - ox) * inv;
            double t2 = (maxX - ox) * inv;
            tEnter = Math.max(tEnter, Math.min(t1, t2));
            tExit = Math.min(tExit, Math.max(t1, t2));
        }

        if (Math.abs(dy) < 1.0E-9) {
            if (oy < minY - 1.0E-7 || oy > maxY + 1.0E-7) return -1.0;
        } else {
            double inv = 1.0 / dy;
            double t1 = (minY - oy) * inv;
            double t2 = (maxY - oy) * inv;
            tEnter = Math.max(tEnter, Math.min(t1, t2));
            tExit = Math.min(tExit, Math.max(t1, t2));
        }

        if (Math.abs(dz) < 1.0E-9) {
            if (oz < minZ - 1.0E-7 || oz > maxZ + 1.0E-7) return -1.0;
        } else {
            double inv = 1.0 / dz;
            double t1 = (minZ - oz) * inv;
            double t2 = (maxZ - oz) * inv;
            tEnter = Math.max(tEnter, Math.min(t1, t2));
            tExit = Math.min(tExit, Math.max(t1, t2));
        }

        return tEnter <= tExit + 1.0E-7 ? tEnter : -1.0;
    }

    /** Matches {@link VoxelShape#clip}: hits if segment overlaps box across {@code s in (ENTRY_EPSILON, 1 - EXIT_EPSILON)}. */
    static boolean slabIntersects(final double ox, final double oy, final double oz,
                                  final double dx, final double dy, final double dz,
                                  final double minX, final double minY, final double minZ,
                                  final double maxX, final double maxY, final double maxZ) {
        double tEnter = Double.NEGATIVE_INFINITY;
        double tExit = Double.POSITIVE_INFINITY;

        if (Math.abs(dx) < 1.0E-9) {
            if (ox < minX + 1.0E-7 || ox > maxX - 1.0E-7) return false;
        } else {
            double inv = 1.0 / dx;
            double t1 = (minX - ox) * inv;
            double t2 = (maxX - ox) * inv;
            tEnter = Math.max(tEnter, Math.min(t1, t2));
            tExit = Math.min(tExit, Math.max(t1, t2));
        }

        if (Math.abs(dy) < 1.0E-9) {
            if (oy < minY + 1.0E-7 || oy > maxY - 1.0E-7) return false;
        } else {
            double inv = 1.0 / dy;
            double t1 = (minY - oy) * inv;
            double t2 = (maxY - oy) * inv;
            tEnter = Math.max(tEnter, Math.min(t1, t2));
            tExit = Math.min(tExit, Math.max(t1, t2));
        }

        if (Math.abs(dz) < 1.0E-9) {
            if (oz < minZ + 1.0E-7 || oz > maxZ - 1.0E-7) return false;
        } else {
            double inv = 1.0 / dz;
            double t1 = (minZ - oz) * inv;
            double t2 = (maxZ - oz) * inv;
            tEnter = Math.max(tEnter, Math.min(t1, t2));
            tExit = Math.min(tExit, Math.max(t1, t2));
        }

        return tEnter <= tExit && tExit > ENTRY_EPSILON && tEnter < 1.0 - EXIT_EPSILON;
    }
}
