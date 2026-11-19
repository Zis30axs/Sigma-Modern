package com.mentalfrostbyte.jello.util.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/** Bounded, read-only pearl recovery search. Never treats a wall impact as a safe landing. */
public final class PearlLandingPlanner {
    private static final double MAX_FLIGHT_DISTANCE = 160;
    // Projectile.getMovementToShoot adds each triangle sample before multiplying by pearl shoot power.
    static final double PEARL_VELOCITY_SPREAD = 0.0172275 * 1.5;
    private static final double MAX_RANGE = 48;
    private static final double MAX_LANDING_DROP = 2.5;

    /** Landing is the old pearl position at impact. Score is remaining turn ticks plus actual flight ticks. */
    public record Plan(float yaw, float pitch, Vec3 landing, Vec3 hitPosition, BlockPos support,
                       List<Vec3> path, double flightTicks, double score) {
        public Plan { path = List.copyOf(path); }
    }

    record Impact(Vec3 position, Direction face, BlockPos block) { }
    record Flight(Vec3 landing, Impact impact, List<Vec3> path, int ticks) {
        Flight { path = List.copyOf(path); }
    }

    /** Small geometry boundary lets tests exercise first-impact and old-position semantics. */
    record Step(boolean clear, @Nullable Impact impact) {
        static final Step OPEN = new Step(true, null), BLOCKED = new Step(false, null);
    }

    interface FlightWorld {
        Step step(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick);
        boolean safeLanding(Vec3 feet);
    }

    private PearlLandingPlanner() { }

    /** A cursor belongs to one held fall. World access stays on the client thread and resumes next tick. */
    public static final class Search {
        private final SearchWorld world;
        private final Vec3 eye;
        private final @Nullable Vec3 preferred;
        private final double range;
        private final List<Column> columns;
        private final PriorityQueue<BlockPos> surfaces;
        private final ArrayDeque<Sample> refinements = new ArrayDeque<>();
        private final ProjectileLaunch.Template template;
        private final ArrayDeque<Sample> alternatives = new ArrayDeque<>();
        private int column, attempts;

        Search(SearchWorld world, Vec3 eye, @Nullable Vec3 preferred, double range, ProjectileLaunch.Template template) {
            this.world = world;
            this.eye = eye;
            this.preferred = finite(preferred) ? preferred : null;
            this.range = range;
            this.template = template;
            this.columns = columns(eye, this.preferred, range);
            this.surfaces = new PriorityQueue<>(Comparator.comparingDouble((BlockPos p) -> p.distToCenterSqr(eye))
                    .thenComparingLong(BlockPos::asLong));
        }

        public boolean exhausted() {
            return column == columns.size() && surfaces.isEmpty() && refinements.isEmpty() && alternatives.isEmpty();
        }

        public @Nullable Plan advance(LocalPlayer player, int remainingTicks, float turnSpeed) {
            return advance(player, remainingTicks, turnSpeed, null);
        }

        public @Nullable Plan advance(LocalPlayer player, int remainingTicks, float turnSpeed, @Nullable Plan incumbent) {
            return advance(player.getEyePosition(), inherited(player), player.getYRot(), player.getXRot(),
                    remainingTicks, turnSpeed, incumbent);
        }

        @Nullable Plan advance(Vec3 launchEye, Vec3 motion, float yaw, float pitch, int remainingTicks, float turnSpeed) {
            return advance(launchEye, motion, yaw, pitch, remainingTicks, turnSpeed, null);
        }

        @Nullable Plan advance(Vec3 launchEye, Vec3 motion, float yaw, float pitch, int remainingTicks,
                               float turnSpeed, @Nullable Plan incumbent) {
            var solver = new ProjectileAim.StationarySolver(template, launchEye, motion);
            int minY = Mth.floor(launchEye.y - MAX_FLIGHT_DISTANCE - 2);
            int maxY = Mth.ceil(solver.highestY());
            long start = System.nanoTime();
            for (int i = 0; i < 256 && column < columns.size(); i++) {
                world.scanColumn(columns.get(column++), minY, maxY, surfaces::add);
                if (System.nanoTime() - start >= 1_500_000L) break;
            }
            // Forward prediction works even when the pearl is still in another hotbar slot. The
            // current look wins equal ETAs and can be used on this tick's confirmed rotation report.
            Plan best = predict(template.launch(launchEye, motion, yaw, pitch), yaw, pitch, 0, remainingTicks, null);
            if (incumbent != null) {
                int turns = turnTicks(yaw, pitch, incumbent.yaw(), incumbent.pitch(), turnSpeed);
                if (turns > 0) {
                    var shot = template.launch(launchEye, motion, incumbent.yaw(), incumbent.pitch());
                    Plan refreshed = predict(shot, incumbent.yaw(), incumbent.pitch(), turns, remainingTicks, best);
                    if (refreshed != null) best = refreshed;
                }
            }
            boolean recoverAlternatives = best == null;
            Sample bestSample = null;
            List<Sample> otherViable = new ArrayList<>();
            long aimStart = System.nanoTime();
            for (int i = 0; i < 16; i++) {
                Sample sample;
                if (recoverAlternatives && !alternatives.isEmpty()) sample = alternatives.removeFirst();
                else if (!refinements.isEmpty() && (surfaces.isEmpty() || attempts++ % 4 == 3)) sample = refinements.removeFirst();
                else if (!surfaces.isEmpty()) sample = new Sample(surfaces.remove(), 0);
                else break;
                Plan plan = aim(sample, solver, remainingTicks, turnSpeed, yaw, pitch);
                if (plan != null && (best == null || plan.score() < best.score()
                        || (bestSample != null && plan.score() == best.score() && nearerPreferred(plan, best)))) {
                    if (bestSample != null) otherViable.add(bestSample);
                    best = plan;
                    bestSample = sample;
                } else if (plan != null) {
                    otherViable.add(sample);
                } else {
                    refine(sample);
                }
                if (System.nanoTime() - aimStart >= 3_000_000L) break;
            }
            // If the winner fails live revalidation, resume with the other viable throws from this slice.
            alternatives.addAll(otherViable);
            if (bestSample != null) refine(bestSample);
            return best;
        }

        private void refine(Sample sample) {
            if (sample.index + 1 < SAMPLE_X.length) refinements.addLast(new Sample(sample.block, sample.index + 1));
        }

        private boolean nearerPreferred(Plan candidate, Plan best) {
            return preferred != null && candidate.landing().distanceToSqr(preferred) < best.landing().distanceToSqr(preferred);
        }

        private @Nullable Plan aim(Sample sample, ProjectileAim.StationarySolver solver,
                                   int remainingTicks, float turnSpeed, float yaw, float pitch) {
            Vec3 target = world.target(sample, eye, range);
            if (target == null || horizontalDistance(eye, target) > range) return null;
            Plan best = null;
            for (var aim : solver.solve(target, remainingTicks - 4)) {
                int turns = turnTicks(yaw, pitch, aim.yaw(), aim.pitch(), turnSpeed);
                Plan candidate = predict(aim.shot(), aim.yaw(), aim.pitch(), turns, remainingTicks, best);
                if (candidate != null) best = candidate;
            }
            return best;
        }

        private @Nullable Plan predict(ProjectileLaunch.Shot shot, float yaw, float pitch, int turns,
                                       int remainingTicks, @Nullable Plan toBeat) {
            int budget = remainingTicks - turns - 5;
            // A high arc can hit intervening terrain before its mathematical intercept. Only the
            // actual traced collision time counts. Stop when even that hit could no longer win.
            int nominalBudget = toBeat == null ? budget : Math.min(budget, (int) toBeat.score() - turns - 1);
            if (nominalBudget < 1) return null;
            Flight flight = trace(shot, world, nominalBudget, true);
            if (flight == null || horizontalDistance(eye, flight.landing()) > range || !spreadSafe(shot, world, budget)) {
                return null;
            }
            return new Plan(yaw, pitch, flight.landing(), flight.impact().position(), flight.impact().block(),
                    flight.path(), flight.ticks(), turns + flight.ticks());
        }
    }

    static int turnTicks(float yaw, float pitch, float wantedYaw, float wantedPitch, float speed) {
        double angle = Math.max(Math.abs(Mth.wrapDegrees(wantedYaw - yaw)), Math.abs(wantedPitch - pitch));
        return angle < 0.01 ? 0 : (int) Math.ceil(angle / speed);
    }

    record Sample(BlockPos block, int index) { }
    record Column(int x, int z) { }
    private static final double[] SAMPLE_X = {0.5, 0.25, 0.75, 0.5, 0.5, 0.25, 0.75, 0.25, 0.75};
    private static final double[] SAMPLE_Z = {0.5, 0.5, 0.5, 0.25, 0.75, 0.25, 0.75, 0.75, 0.25};

    /** Progressively halve the lattice spacing. Each intersecting column is visited exactly once. */
    static List<Column> columns(Vec3 eye, @Nullable Vec3 preferred, double range) {
        int cx = Mth.floor(eye.x), cz = Mth.floor(eye.z), radius = Mth.ceil(range) + 1;
        List<Column> result = new ArrayList<>();
        Column remembered = preferred == null ? null : new Column(Mth.floor(preferred.x), Mth.floor(preferred.z));
        if (remembered != null && touchesDisk(remembered, eye, range)) result.add(remembered);
        // Seed the nearest blocks before the wide survey so short, flat throws get an early trial.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Column near = new Column(cx + dx, cz + dz);
                if (!near.equals(remembered) && touchesDisk(near, eye, range)) result.add(near);
            }
        }
        for (int stride = 8; stride >= 1; stride /= 2) {
            for (int r = stride; r <= radius; r += stride) {
                if (r <= 2) continue;
                for (int k = -r; k < r; k += stride) {
                    if (stride < 8 && r % (2 * stride) == 0 && k % (2 * stride) == 0) continue;
                    int dx = k, dz = -r;
                    for (int side = 0; side < 4; side++) {
                        Column point = new Column(cx + dx, cz + dz);
                        if (!point.equals(remembered) && touchesDisk(point, eye, range)) result.add(point);
                        int oldX = dx;
                        dx = -dz;
                        dz = oldX;
                    }
                }
            }
        }
        return result;
    }

    private static boolean touchesDisk(Column c, Vec3 eye, double range) {
        double dx = eye.x - Mth.clamp(eye.x, c.x, c.x + 1);
        double dz = eye.z - Mth.clamp(eye.z, c.z, c.z + 1);
        return dx * dx + dz * dz <= range * range;
    }

    public static @Nullable Search start(LocalPlayer player, @Nullable Vec3 preferred, double range) {
        if (player == null || !player.isAlive() || !finite(player.getEyePosition())
                || !Double.isFinite(range) || range < 1) return null;
        var template = pearlTemplate();
        return template == null ? null : new Search(new World(player), player.getEyePosition(), preferred, Math.min(MAX_RANGE, range), template);
    }

    /** Synchronous entry point for callers outside the tick-driven AutoPearl state machine. */
    public static @Nullable Plan find(LocalPlayer player, @Nullable Vec3 preferred, double range) {
        Search search = start(player, preferred, range);
        while (search != null && !search.exhausted()) {
            Plan plan = search.advance(player, 200, 180F);
            if (plan != null) return plan;
        }
        return null;
    }

    /** Replays the chosen visible rotation from the current origin immediately before item use. */
    public static boolean revalidate(LocalPlayer player, Plan plan) {
        return revalidate(player, plan, 160);
    }

    public static boolean revalidate(LocalPlayer player, Plan plan, int remainingFlightTicks) {
        if (player == null || plan == null || !player.isAlive() || !Float.isFinite(plan.yaw())
                || !Float.isFinite(plan.pitch()) || Math.abs(plan.pitch()) > 90) return false;
        ProjectileLaunch.Template template = pearlTemplate();
        if (template == null) return false;
        float yaw = player.getYRot(), pitch = player.getXRot();
        if (Math.abs(Mth.wrapDegrees(yaw - plan.yaw())) >= 0.01F || Math.abs(pitch - plan.pitch()) >= 0.01F) return false;
        ProjectileLaunch.Shot shot = template.launch(player.getEyePosition(), inherited(player), yaw, pitch);
        World world = new World(player);
        Flight flight = trace(shot, world, remainingFlightTicks, false);
        return flight != null && flight.impact().block().equals(plan.support())
                && flight.landing().distanceToSqr(plan.landing()) < 0.25 && spreadSafe(shot, world, remainingFlightTicks);
    }

    /**
     * Deterministic extreme samples of vanilla's three independent triangle distributions.
     * They do not consume RNG or normalize the perturbed velocity (vanilla does neither after sampling).
     * A nominal safe trace is required separately; each corner must also end on a supported top surface.
     */
    static boolean spreadSafe(ProjectileLaunch.Shot shot, FlightWorld world) {
        return spreadSafe(shot, world, shot.maxTicks());
    }

    private static boolean spreadSafe(ProjectileLaunch.Shot shot, FlightWorld world, int ticks) {
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                for (int z = -1; z <= 1; z += 2) {
                    Vec3 velocity = shot.velocity().add(x * PEARL_VELOCITY_SPREAD,
                            y * PEARL_VELOCITY_SPREAD, z * PEARL_VELOCITY_SPREAD);
                    ProjectileLaunch.Shot perturbed = new ProjectileLaunch.Shot(shot.origin(), velocity, shot.physics(),
                            shot.gravity(), shot.airDrag(), shot.waterDrag(), shot.width(), shot.height(), shot.maxTicks(), shot.preview());
                    if (trace(perturbed, world, ticks, false) == null) return false;
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

    /** Simulates full ticks, not just the solver's intercept prefix (which can finish on ascent). */
    static @Nullable Flight trace(ProjectileLaunch.Shot shot, FlightWorld world) {
        return trace(shot, world, 160, true);
    }

    private static @Nullable Flight trace(ProjectileLaunch.Shot shot, FlightWorld world, int ticks, boolean capturePath) {
        if (shot == null || !finite(shot.origin()) || !finite(shot.velocity())) return null;
        Vec3 position = shot.origin(), velocity = shot.velocity();
        List<Vec3> path = capturePath ? new ArrayList<>() : List.of();
        if (capturePath) path.add(position);
        double travelled = 0;
        for (int tick = 1; tick <= Math.min(ticks, shot.maxTicks()); tick++) {
            velocity = velocity.add(0, -shot.gravity(), 0).scale(shot.airDrag());
            Vec3 next = position.add(velocity);
            travelled += velocity.length();
            if (travelled > MAX_FLIGHT_DISTANCE || !finite(next)) return null;
            Step step = world.step(position, next, shot, tick);
            if (!step.clear()) return null;
            Impact hit = step.impact();
            if (hit != null) {
                // ThrownEnderpearl.onHit teleports to oldPosition(), before this tick's movement.
                if (hit.face() != Direction.UP || velocity.y >= 0 || !world.safeLanding(position)) return null;
                if (capturePath) path.add(hit.position());
                return new Flight(position, hit, path, tick);
            }
            if (capturePath) path.add(next);
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

    /** Visit every exposed collision surface in nonempty sections, including cave floors below the heightmap. */
    static void scanSurfaces(BlockGetter level, Column column, int minY, int top,
                             IntFunction<@Nullable PalettedContainer<BlockState>> sectionAtY, Consumer<BlockPos> emit) {
        var pos = new BlockPos.MutableBlockPos();
        boolean covered = false;
        for (int sectionTop = top; sectionTop >= minY; sectionTop = (sectionTop & ~15) - 1) {
            var states = sectionAtY.apply(sectionTop);
            if (states == null) {
                covered = false;
                continue;
            }
            for (int y = sectionTop; y >= Math.max(minY, sectionTop & ~15); y--) {
                BlockState state = states.get(column.x & 15, y & 15, column.z & 15);
                if (state.isAir()) {
                    covered = false;
                    continue;
                }
                pos.set(column.x, y, column.z);
                if (!covered && state.getFluidState().isEmpty() && !hazard(state)
                        && !state.getCollisionShape(level, pos).isEmpty()) emit.accept(pos.immutable());
                covered = state.isCollisionShapeFullBlock(level, pos);
            }
        }
    }

    interface SearchWorld extends FlightWorld {
        void scanColumn(Column column, int minY, int maxY, Consumer<BlockPos> emit);
        @Nullable Vec3 target(Sample sample, Vec3 eye, double range);
    }

    private static final class World implements SearchWorld {
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

        @Override public void scanColumn(Column column, int minY, int maxY, Consumer<BlockPos> emit) {
            int x = column.x, z = column.z;
            LevelChunk chunk = this.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) return;
            // WORLD_SURFACE includes leaves and fluids. A missing heightmap is optional acceleration.
            minY = Math.max(minY, level.getMinY());
            maxY = Math.min(maxY, level.getMaxY());
            int top = chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)
                    ? Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)) : maxY;
            scanSurfaces(level, column, minY, top, y -> {
                var section = chunk.getSection(chunk.getSectionIndex(y));
                return section.hasOnlyAir() ? null : section.getStates();
            }, emit);
        }

        @Override public @Nullable Vec3 target(Sample sample, Vec3 eye, double range) {
            BlockPos pos = sample.block;
            if (!loaded(pos.getX(), pos.getZ(), pos.getX(), pos.getZ())) return null;
            BlockState state = this.level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty() || hazard(state)) return null;
            double x = SAMPLE_X[sample.index], z = SAMPLE_Z[sample.index];
            // A boundary cell can intersect the disk while its center is outside. Aim at the
            // nearest interior point on its first try; the surface query still checks its actual height.
            if (sample.index == 0 && Math.hypot(pos.getX() + x - eye.x, pos.getZ() + z - eye.z) > range) {
                x = Mth.clamp(eye.x - pos.getX(), 0.0001, 0.9999);
                z = Mth.clamp(eye.z - pos.getZ(), 0.0001, 0.9999);
            }
            var shape = state.getCollisionShape(this.level, pos);
            double top = shape.max(Direction.Axis.Y, z, x);
            if (!Double.isFinite(top)) return null;
            return new Vec3(pos.getX() + x, pos.getY() + top - 0.015, pos.getZ() + z);
        }

        @Override public Step step(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
            if (!this.trajectory.loaded(from, to)) return Step.BLOCKED;
            BlockHitResult block = clip(from, to);
            Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
            if (!clearFluidsAndHazards(new AABB(from, end).inflate(shot.width() * 0.5))) return Step.BLOCKED;
            ProjectileTrajectories.Hit first = this.trajectory.collision(from, to, shot, tick, block);
            if (first == null) return Step.OPEN;
            return first.entityHit() ? Step.BLOCKED
                    : new Step(true, new Impact(block.getLocation(), block.getDirection(), block.getBlockPos()));
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
