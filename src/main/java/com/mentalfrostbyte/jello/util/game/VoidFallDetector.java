package com.mentalfrostbyte.jello.util.game;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Conservative, read-only void detection. Unknown terrain is never treated as empty terrain. */
public final class VoidFallDetector {
    public static final Limits DEFAULTS = new Limits(3.75, 10, 6);
    private static final int STABLE_GROUND_TICKS = 3;
    private static final int CONFIRMED_VOID_TICKS = 3;
    private static final int MAX_SAFE_AGE = 200;
    private static final int MAX_SAFE_POSITIONS = 12;
    private static final int MAX_PROBE_CELLS = 24_576;
    private static final double HORIZONTAL_LOOKAHEAD = 6;
    private static final double MAX_HORIZONTAL_LOOKAHEAD = 12;
    private static final double WORLD_LIMIT = 30_000_000;

    public enum Support { SUPPORTED, VOID, UNKNOWN }

    public record Limits(double minimumFall, int minimumAirTicks, int minimumDescendingTicks) {
        public Limits {
            if (!Double.isFinite(minimumFall) || minimumFall < 0.5 || minimumFall > 128
                    || minimumAirTicks < 1 || minimumAirTicks > 200
                    || minimumDescendingTicks < 1 || minimumDescendingTicks > 200) {
                throw new IllegalArgumentException("Invalid void detection limits");
            }
        }
    }

    /** Safe positions are newest first and belong only to the current player/world. */
    public record Result(boolean dangerous, Support support, double fallenDistance, int airborneTicks,
                         int descendingTicks, @Nullable Vec3 lastSafePosition, List<Vec3> safePositions) {
        public Result { safePositions = List.copyOf(safePositions); }
    }

    private final Gate gate = new Gate();
    private @Nullable LocalPlayer owner;
    private @Nullable Level level;
    private int lastTick = Integer.MIN_VALUE;
    private Result lastResult = emptyResult();

    public Result tick(LocalPlayer player, Limits limits) {
        if (this.owner != player || this.level != player.level()) {
            reset();
            this.owner = player;
            this.level = player.level();
        }
        if (this.lastTick == player.tickCount) return this.lastResult;
        this.lastTick = player.tickCount;
        boolean eligible = eligible(player);
        boolean grounded = eligible && stableFooting(player);
        Support support = grounded ? Support.SUPPORTED
                : eligible && player.getDeltaMovement().y < -0.04 ? probe(player) : Support.UNKNOWN;
        this.lastResult = this.gate.tick(new Sample(player.position(), player.getDeltaMovement().y,
                eligible, grounded, support), limits);
        return this.lastResult;
    }

    public void reset() {
        this.gate.reset();
        this.owner = null;
        this.level = null;
        this.lastTick = Integer.MIN_VALUE;
        this.lastResult = emptyResult();
    }

    /** Checks the full loaded column under the current and near-future horizontal footprint. */
    public static Support probe(LocalPlayer player) {
        return probe(new WorldEnvironment(player), player.getBoundingBox(), player.getDeltaMovement());
    }

    /** Actual solid footing, excluding fluids, vehicles, flight and an onGround flag alone. */
    public static boolean stableFooting(LocalPlayer player) {
        if (!eligible(player) || !player.onGround()) return false;
        AABB bounds = player.getBoundingBox();
        if (!validBounds(bounds)) return false;
        AABB feet = new AABB(bounds.minX + 0.01, bounds.minY - 0.06, bounds.minZ + 0.01,
                bounds.maxX - 0.01, bounds.minY + 0.005, bounds.maxZ - 0.01);
        WorldEnvironment environment = new WorldEnvironment(player);
        return loaded(environment, feet) && !player.level().noBlockCollision(player, feet);
    }

    private static boolean eligible(LocalPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator()
                && !player.isPassenger() && !player.isSleeping() && !player.noPhysics
                && !player.getAbilities().flying && !player.isFallFlying()
                && !player.isInWater() && !player.isInLava() && !player.onClimbable();
    }

    private static Result emptyResult() {
        return new Result(false, Support.UNKNOWN, 0, 0, 0, null, List.of());
    }

    interface Environment {
        int minY();
        int maxY();
        boolean loaded(int chunkX, int chunkZ);
        boolean catches(AABB volume);
    }

    static Support probe(Environment environment, AABB bounds, Vec3 movement) {
        if (!validBounds(bounds) || !finite(movement)) return Support.UNKNOWN;
        double dx = movement.x * HORIZONTAL_LOOKAHEAD;
        double dz = movement.z * HORIZONTAL_LOOKAHEAD;
        if (Math.abs(dx) > MAX_HORIZONTAL_LOOKAHEAD || Math.abs(dz) > MAX_HORIZONTAL_LOOKAHEAD
                || environment.minY() > environment.maxY()) return Support.UNKNOWN;
        // Include edge/corner support and every point between now and the predicted footprint.
        AABB sweep = bounds.inflate(0.15, 0, 0.15).expandTowards(dx, 0, dz);
        if (!loaded(environment, sweep)) return Support.UNKNOWN;
        double top = Math.min(bounds.minY - 0.001, environment.maxY() + 1.0);
        if (top <= environment.minY()) return Support.VOID;
        AABB column = new AABB(sweep.minX, environment.minY(), sweep.minZ,
                sweep.maxX, top, sweep.maxZ);
        // The collision iterator also visits neighbouring blocks for protruding shapes.
        long width = (long) Mth.floor(column.maxX) - Mth.floor(column.minX) + 3;
        long depth = (long) Mth.floor(column.maxZ) - Mth.floor(column.minZ) + 3;
        long height = (long) Mth.floor(column.maxY) - Mth.floor(column.minY) + 3;
        if (width * depth * height > MAX_PROBE_CELLS) return Support.UNKNOWN;
        return environment.catches(column) ? Support.SUPPORTED : Support.VOID;
    }

    private static boolean loaded(Environment environment, AABB bounds) {
        // Collision shapes can read adjacent blocks; require those chunks to be present as well.
        int minX = Mth.floor(bounds.minX - 1) >> 4;
        int maxX = Mth.floor(bounds.maxX + 1) >> 4;
        int minZ = Mth.floor(bounds.minZ - 1) >> 4;
        int maxZ = Mth.floor(bounds.maxZ + 1) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!environment.loaded(x, z)) return false;
            }
        }
        return true;
    }

    private record WorldEnvironment(LocalPlayer player) implements Environment {
        public int minY() { return this.player.level().getMinY(); }
        public int maxY() { return this.player.level().getMaxY(); }
        public boolean loaded(int chunkX, int chunkZ) {
            // ClientLevel.hasChunk always returns true; the source checks actual storage.
            return this.player.level().getChunkSource().hasChunk(chunkX, chunkZ);
        }
        public boolean catches(AABB volume) {
            return !this.player.level().noBlockCollision(this.player, volume, true)
                    || !this.player.level().noEntityCollision(this.player, volume);
        }
    }

    record Sample(Vec3 position, double verticalVelocity, boolean eligible, boolean grounded, Support support) { }

    /** Pure temporal gate; observed Y changes are independent of NoFall's fallDistance edits. */
    static final class Gate {
        private final List<Vec3> safePositions = new ArrayList<>();
        private @Nullable Vec3 previous;
        private int stableTicks;
        private int airborneTicks;
        private int descendingTicks;
        private int voidTicks;
        private int safeAge;
        private double fallenDistance;

        Result tick(Sample sample, Limits limits) {
            if (!sample.eligible() || !finite(sample.position()) || !Double.isFinite(sample.verticalVelocity())) {
                reset();
                return emptyResult();
            }
            Vec3 previous = this.previous;
            if (previous != null && previous.distanceToSqr(sample.position()) > 64) {
                // Teleports/corrections require fresh footing; a discontinuity is not a fall.
                reset();
                previous = null;
            }
            this.previous = sample.position();
            if (sample.grounded()) {
                this.airborneTicks = this.descendingTicks = this.voidTicks = 0;
                this.fallenDistance = 0;
                this.stableTicks = Math.min(STABLE_GROUND_TICKS, this.stableTicks + 1);
                if (this.stableTicks >= STABLE_GROUND_TICKS) remember(sample.position());
            } else {
                this.stableTicks = 0;
                this.airborneTicks = Math.min(1000, this.airborneTicks + 1);
                this.safeAge = Math.min(MAX_SAFE_AGE + 1, this.safeAge + 1);
                double drop = previous == null ? 0 : previous.y - sample.position().y;
                if (drop > 0.015 && sample.verticalVelocity() < -0.04) {
                    this.descendingTicks = Math.min(1000, this.descendingTicks + 1);
                    this.fallenDistance += drop;
                } else {
                    this.descendingTicks = 0;
                    this.fallenDistance = 0;
                }
                this.voidTicks = sample.support() == Support.VOID ? Math.min(1000, this.voidTicks + 1) : 0;
            }
            boolean dangerous = sample.support() == Support.VOID && !this.safePositions.isEmpty()
                    && this.safeAge <= MAX_SAFE_AGE && this.voidTicks >= CONFIRMED_VOID_TICKS
                    && this.airborneTicks >= limits.minimumAirTicks()
                    && this.descendingTicks >= limits.minimumDescendingTicks()
                    && this.fallenDistance >= limits.minimumFall();
            return new Result(dangerous, sample.support(), this.fallenDistance, this.airborneTicks,
                    this.descendingTicks, this.safePositions.isEmpty() ? null : this.safePositions.getFirst(),
                    this.safePositions);
        }

        private void remember(Vec3 position) {
            this.safeAge = 0;
            if (!this.safePositions.isEmpty() && this.safePositions.getFirst().distanceToSqr(position) < 1) {
                return;
            } else {
                this.safePositions.addFirst(position);
                if (this.safePositions.size() > MAX_SAFE_POSITIONS) this.safePositions.removeLast();
            }
        }

        void reset() {
            this.previous = null;
            this.safePositions.clear();
            this.stableTicks = this.airborneTicks = this.descendingTicks = this.voidTicks = this.safeAge = 0;
            this.fallenDistance = 0;
        }
    }

    private static boolean finite(Vec3 position) {
        return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                && Math.abs(position.x) < WORLD_LIMIT && Math.abs(position.y) < WORLD_LIMIT
                && Math.abs(position.z) < WORLD_LIMIT;
    }

    private static boolean validBounds(AABB bounds) {
        return finite(new Vec3(bounds.minX, bounds.minY, bounds.minZ))
                && finite(new Vec3(bounds.maxX, bounds.maxY, bounds.maxZ))
                && bounds.maxX > bounds.minX && bounds.maxZ > bounds.minZ
                && bounds.maxX - bounds.minX < 4 && bounds.maxZ - bounds.minZ < 4;
    }
}
