package com.mentalfrostbyte.jello.util.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/** Bounded, read-only pearl recovery search. Never treats a wall impact as a safe landing. */
public final class PearlLandingPlanner {
    private static final int MAX_COLUMNS = 1024;
    private static final int MAX_BLOCK_CHECKS = 24_000;
    private static final int MAX_CANDIDATES = 96;
    private static final int MAX_AIMS = 48;
    static final int MAX_ROBUST_CANDIDATES = 8;
    // Projectile.getMovementToShoot adds each triangle sample before multiplying by pearl shoot power.
    static final double PEARL_VELOCITY_SPREAD = 0.0172275 * 1.5;
    private static final double MAX_RANGE = 48;
    private static final double MAX_LANDING_DROP = 2.5;

    /** landing is the actual teleport destination (the collision tick's old pearl position). */
    public record Plan(float yaw, float pitch, Vec3 landing, Vec3 hitPosition, BlockPos support,
                       List<Vec3> path, double flightTicks, double score) {
        public Plan { path = List.copyOf(path); }
    }

    private record Candidate(Vec3 point, double rank) { }
    record RankedPlan(Plan plan, ProjectileLaunch.Shot shot) { }
    record Impact(Vec3 position, Direction face, BlockPos block) { }
    record Flight(Vec3 landing, Impact impact, List<Vec3> path, int ticks) {
        Flight { path = List.copyOf(path); }
    }

    /** Small geometry boundary lets tests exercise first-impact and old-position semantics. */
    interface FlightWorld {
        boolean clearSegment(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick);
        @Nullable Impact impact(Vec3 from, Vec3 to);
        boolean safeLanding(Vec3 feet);
    }

    private PearlLandingPlanner() { }

    public static @Nullable Plan find(LocalPlayer player, @Nullable Vec3 preferredLanding, double range) {
        if (player == null || !player.isAlive() || !Double.isFinite(range) || range < 1) return null;
        range = Math.min(MAX_RANGE, range);
        World world = new World(player);
        Vec3 eye = player.getEyePosition();
        if (!finite(eye)) return null;
        Vec3 preferred = finite(preferredLanding) && eye.distanceToSqr(preferredLanding) <= range * range * 4
                ? preferredLanding : null;
        List<Candidate> candidates = candidates(world, eye, preferred, range);
        ProjectileLaunch.Template template = pearlTemplate();
        if (template == null) return null;
        Vec3 inherited = inherited(player);
        List<List<ProjectileAim.Aim>> solutions = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            // Aim slightly through the top so a rounded float endpoint still intersects its surface.
            solutions.add(ProjectileAim.solveAll(new ProjectileAim.Input(template, eye, inherited,
                    candidate.point().add(0, -0.015, 0), Vec3.ZERO)));
        }
        // Prefer short flight over a long loft. Only try high arcs when every low arc is obstructed/unsafe.
        for (int arc = 0; arc < 2; arc++) {
            List<RankedPlan> ranked = new ArrayList<>();
            for (int index = 0; index < solutions.size(); index++) {
                List<ProjectileAim.Aim> aims = solutions.get(index);
                if (aims.size() <= arc) continue;
                ProjectileAim.Aim aim = aims.get(arc);
                Flight flight = trace(aim.shot(), world);
                if (flight == null || horizontalDistance(eye, flight.landing()) > range) continue;
                double score = flight.ticks() + eye.distanceTo(flight.landing()) * 0.15
                        + (preferred == null ? 0 : preferred.distanceTo(flight.landing()) * 0.35);
                Plan plan = new Plan(aim.yaw(), aim.pitch(), flight.landing(), flight.impact().position(),
                        flight.impact().block(), flight.path(), flight.ticks(), score);
                ranked.add(new RankedPlan(plan, aim.shot()));
            }
            Plan best = firstRobust(ranked, world);
            if (best != null) return best;
        }
        return null;
    }

    /** Replays the chosen visible rotation from the current origin immediately before item use. */
    public static boolean revalidate(LocalPlayer player, Plan plan) {
        if (player == null || plan == null || !player.isAlive() || !Float.isFinite(plan.yaw())
                || !Float.isFinite(plan.pitch()) || Math.abs(plan.pitch()) > 90) return false;
        ProjectileLaunch.Template template = pearlTemplate();
        if (template == null) return false;
        ProjectileLaunch.Shot shot = template.launch(player.getEyePosition(), inherited(player), plan.yaw(), plan.pitch());
        World world = new World(player);
        Flight flight = trace(shot, world);
        return flight != null && flight.impact().block().equals(plan.support())
                && flight.landing().distanceToSqr(plan.landing()) < 0.25 && spreadSafe(shot, world);
    }

    /** Expensive spread checks are limited to the best nominal candidates, independently for each arc. */
    static @Nullable Plan firstRobust(List<RankedPlan> candidates, FlightWorld world) {
        List<RankedPlan> ranked = candidates.stream().sorted(Comparator.comparingDouble(value -> value.plan().score())).toList();
        for (int index = 0; index < Math.min(MAX_ROBUST_CANDIDATES, ranked.size()); index++) {
            RankedPlan candidate = ranked.get(index);
            if (spreadSafe(candidate.shot(), world)) return candidate.plan();
        }
        return null;
    }

    /**
     * Deterministic extreme samples of vanilla's three independent triangle distributions.
     * They do not consume RNG or normalize the perturbed velocity (vanilla does neither after sampling).
     * A nominal safe trace is required separately; each corner must also end on a supported top surface.
     */
    static boolean spreadSafe(ProjectileLaunch.Shot shot, FlightWorld world) {
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                for (int z = -1; z <= 1; z += 2) {
                    Vec3 velocity = shot.velocity().add(x * PEARL_VELOCITY_SPREAD,
                            y * PEARL_VELOCITY_SPREAD, z * PEARL_VELOCITY_SPREAD);
                    ProjectileLaunch.Shot perturbed = new ProjectileLaunch.Shot(shot.origin(), velocity, shot.physics(),
                            shot.gravity(), shot.airDrag(), shot.waterDrag(), shot.width(), shot.height(), shot.maxTicks(), shot.preview());
                    if (trace(perturbed, world) == null) return false;
                }
            }
        }
        return true;
    }

    /** Rechecks remembered ground; a removed platform or changed fluid never stays trusted. */
    public static boolean isSafeStanding(LocalPlayer player, Vec3 feet) {
        return player != null && finite(feet) && new World(player).safeFeet(feet, 0.12);
    }

    /** A pearl arrives above its support; allow the short, clear descent already checked by the plan. */
    public static boolean isSafeLanding(LocalPlayer player, Vec3 feet) {
        return player != null && finite(feet) && new World(player).safeFeet(feet, MAX_LANDING_DROP);
    }

    private static ProjectileLaunch.@Nullable Template pearlTemplate() {
        return ProjectileLaunch.template(new ItemStack(Items.ENDER_PEARL), 0);
    }

    private static Vec3 inherited(LocalPlayer player) {
        return ProjectileLaunch.inheritedMovement(player.getKnownMovement(), player.onGround());
    }

    private static List<Candidate> candidates(World world, Vec3 eye, @Nullable Vec3 preferred, double range) {
        List<Candidate> result = new ArrayList<>();
        Set<Long> columns = new HashSet<>();
        Set<BlockPos> checked = new HashSet<>();
        int[] work = {0};
        if (preferred != null) scanRings(world, eye, preferred, preferred, 4, range, columns, checked, result, work);
        scanRings(world, eye, eye, preferred, Mth.ceil(range), range, columns, checked, result, work);
        result.sort(Comparator.comparingDouble(Candidate::rank));
        return List.copyOf(result.subList(0, Math.min(MAX_AIMS, result.size())));
    }

    private static void scanRings(World world, Vec3 eye, Vec3 center, @Nullable Vec3 preferred, int radius,
                                  double range, Set<Long> columns, Set<BlockPos> checked,
                                  List<Candidate> result, int[] work) {
        int cx = Mth.floor(center.x), cz = Mth.floor(center.z);
        for (int ring = 0; ring <= radius; ring++) {
            int step = ring > 12 ? 2 : 1;
            for (int dx = -ring; dx <= ring; dx += step) {
                for (int dz = -ring; dz <= ring; dz += step) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    if (columns.size() >= MAX_COLUMNS || work[0] >= MAX_BLOCK_CHECKS || result.size() >= MAX_CANDIDATES) return;
                    int x = cx + dx, z = cz + dz;
                    if (horizontalDistance(eye, new Vec3(x + 0.5, eye.y, z + 0.5)) > range) continue;
                    long key = ((long) x << 32) ^ (z & 0xffffffffL);
                    if (!columns.add(key) || !world.loaded(x - 1, z - 1, x + 2, z + 2)) continue;
                    // Heightmap is read only after verifying the chunk exists; no search generates chunks.
                    int top = world.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    candidate(world, eye, preferred, new BlockPos(x, top, z), checked, result, work);
                    int middle = Mth.floor(preferred != null ? preferred.y : eye.y) - 1;
                    // Search every height near the freeze/remembered position, then a sparse cave sample.
                    // Outdoor/End platforms are still found at their exact height through the heightmap.
                    for (int distance = 0; distance <= 24; distance += ring <= 4 ? 1 : 6) {
                        candidate(world, eye, preferred, new BlockPos(x, middle - distance, z), checked, result, work);
                        if (distance > 0) candidate(world, eye, preferred, new BlockPos(x, middle + distance, z), checked, result, work);
                        if (work[0] >= MAX_BLOCK_CHECKS || result.size() >= MAX_CANDIDATES) break;
                    }
                }
            }
        }
    }

    private static void candidate(World world, Vec3 eye, @Nullable Vec3 preferred, BlockPos pos,
                                   Set<BlockPos> checked, List<Candidate> result, int[] work) {
        if (work[0] >= MAX_BLOCK_CHECKS || result.size() >= MAX_CANDIDATES || !checked.add(pos)
                || pos.getY() < world.level.getMinY() || pos.getY() > world.level.getMaxY()
                || pos.getY() < eye.y - 36 || pos.getY() > eye.y + 28) return;
        work[0]++;
        BlockState state = world.level.getBlockState(pos);
        if (state.isAir() || hazard(state) || !state.getFluidState().isEmpty()) return;
        var shape = state.getCollisionShape(world.level, pos, CollisionContext.empty());
        if (shape.isEmpty()) return;
        Vec3 feet = new Vec3(pos.getX() + 0.5, pos.getY() + shape.max(Direction.Axis.Y), pos.getZ() + 0.5);
        if (!world.safeFeet(feet, 0.12)) return;
        double rank = eye.distanceTo(feet) + (preferred == null ? 0 : preferred.distanceTo(feet) * 0.2);
        result.add(new Candidate(feet, rank));
    }

    /** Simulates full ticks, not just the solver's intercept prefix (which can finish on ascent). */
    static @Nullable Flight trace(ProjectileLaunch.Shot shot, FlightWorld world) {
        if (shot == null || !finite(shot.origin()) || !finite(shot.velocity())) return null;
        Vec3 position = shot.origin(), velocity = shot.velocity();
        List<Vec3> path = new ArrayList<>();
        path.add(position);
        double travelled = 0;
        for (int tick = 1; tick <= Math.min(160, shot.maxTicks()); tick++) {
            velocity = velocity.add(0, -shot.gravity(), 0).scale(shot.airDrag());
            Vec3 next = position.add(velocity);
            travelled += velocity.length();
            if (travelled > 160 || !finite(next) || !world.clearSegment(position, next, shot, tick)) return null;
            Impact hit = world.impact(position, next);
            if (hit != null) {
                // ThrownEnderpearl.onHit teleports to oldPosition(), before this tick's movement.
                if (hit.face() != Direction.UP || velocity.y >= 0 || !world.safeLanding(position)) return null;
                path.add(hit.position());
                return new Flight(position, hit, path, tick);
            }
            path.add(next);
            position = next;
        }
        return null;
    }

    private static boolean hazard(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY) || state.is(Blocks.NETHER_PORTAL);
    }

    private static boolean finite(@Nullable Vec3 position) {
        return position != null && Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                && Math.abs(position.x) < 30_000_000 && Math.abs(position.y) < 30_000_000 && Math.abs(position.z) < 30_000_000;
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }

    private static final class World implements FlightWorld {
        private final LocalPlayer player;
        private final Level level;
        private final TrajectoryWorld trajectory;
        private final double halfWidth;
        private final double height;

        World(LocalPlayer player) {
            this.player = player;
            this.level = player.level();
            this.trajectory = new TrajectoryWorld(player, 1F);
            var dimensions = player.getDimensions(Pose.STANDING);
            this.halfWidth = dimensions.width() * 0.5 + 0.06;
            this.height = dimensions.height();
        }

        @Override public boolean clearSegment(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
            if (!this.trajectory.loaded(from, to)) return false;
            AABB swept = new AABB(from, to).inflate(shot.width() * 0.5);
            if (!clearFluidsAndHazards(swept)) return false;
            ProjectileTrajectories.Hit hit = this.trajectory.collision(from, to, shot, tick);
            return hit == null || !hit.entityHit();
        }

        @Override public @Nullable Impact impact(Vec3 from, Vec3 to) {
            BlockHitResult hit = clip(from, to);
            return hit.getType() == HitResult.Type.MISS ? null : new Impact(hit.getLocation(), hit.getDirection(), hit.getBlockPos());
        }

        @Override public boolean safeLanding(Vec3 feet) { return safeFeet(feet, MAX_LANDING_DROP); }

        boolean safeFeet(Vec3 feet, double drop) {
            if (!finite(feet) || feet.y < this.level.getMinY() || feet.y + this.height > this.level.getMaxY() + 1) return false;
            AABB body = new AABB(feet.x - this.halfWidth, feet.y + 0.003, feet.z - this.halfWidth,
                    feet.x + this.halfWidth, feet.y + this.height, feet.z + this.halfWidth);
            if (!loaded(body.minX - 1, body.minZ - 1, body.maxX + 1, body.maxZ + 1)
                    || !this.level.noCollision(this.player, body) || !clearFluidsAndHazards(body)) return false;
            double lowest = Double.POSITIVE_INFINITY, highest = Double.NEGATIVE_INFINITY;
            for (double x : new double[] {-this.halfWidth, 0, this.halfWidth}) {
                for (double z : new double[] {-this.halfWidth, 0, this.halfWidth}) {
                    Vec3 from = feet.add(x, 0.06, z), to = feet.add(x, -drop, z);
                    BlockHitResult hit = clip(from, to);
                    if (hit.getType() == HitResult.Type.MISS || hit.getDirection() != Direction.UP
                            || hazard(this.level.getBlockState(hit.getBlockPos()))) return false;
                    lowest = Math.min(lowest, hit.getLocation().y);
                    highest = Math.max(highest, hit.getLocation().y);
                }
            }
            // A footprint straddling a cliff or a steep stair edge is not a stable rescue destination.
            if (highest - lowest > 0.13) return false;
            AABB descent = new AABB(body.minX, highest + 0.003, body.minZ, body.maxX, body.maxY, body.maxZ);
            return this.level.noCollision(this.player, descent) && clearFluidsAndHazards(descent.inflate(0.02));
        }

        private BlockHitResult clip(Vec3 from, Vec3 to) {
            return this.level.clipIncludingBorder(new ClipContext(from, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        }

        boolean loaded(double minX, double minZ, double maxX, double maxZ) {
            for (int x = Mth.floor(minX) >> 4; x <= Mth.floor(maxX) >> 4; x++) {
                for (int z = Mth.floor(minZ) >> 4; z <= Mth.floor(maxZ) >> 4; z++) {
                    if (!this.level.getChunkSource().hasChunk(x, z)) return false;
                }
            }
            return true;
        }

        private boolean clearFluidsAndHazards(AABB box) {
            if (!loaded(box.minX, box.minZ, box.maxX, box.maxZ)) return false;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
                for (int y = Math.max(this.level.getMinY(), Mth.floor(box.minY)); y <= Math.min(this.level.getMaxY(), Mth.floor(box.maxY)); y++) {
                    for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                        BlockState state = this.level.getBlockState(pos.set(x, y, z));
                        if (!state.getFluidState().isEmpty() || hazard(state)) return false;
                    }
                }
            }
            return true;
        }
    }
}
