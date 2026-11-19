package com.mentalfrostbyte.jello.util.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Tick-based comparisons include the first world impact rather than just the solver's intercept. */
class PearlImpactSelectionTest {
    private static final Vec3 EYE = new Vec3(.5, 4.1, .5);
    private static final BlockPos FAR = new BlockPos(0, 0, 24);
    private static final BlockPos NEAR = new BlockPos(0, 0, 9);
    private static final ProjectileLaunch.Template PEARL = new ProjectileLaunch.Template(
            1.5F, 0, ProjectileLaunch.Physics.THROWABLE, .03, .99F, .8F, -.1F, .25F, .25F, 160, true);

    private static final class Platform implements PearlLandingPlanner.SearchWorld {
        private final boolean wall;
        private final List<BlockPos> targets;

        Platform(boolean wall, BlockPos... targets) {
            this.wall = wall;
            this.targets = List.of(targets);
        }

        public void scanColumn(PearlLandingPlanner.Column column, int min, int max, Consumer<BlockPos> emit) {
            for (BlockPos p : targets) {
                if (column.x() == p.getX() && column.z() == p.getZ()) emit.accept(p);
            }
        }

        public Vec3 target(PearlLandingPlanner.Sample sample, Vec3 eye, double range) {
            return sample.index() == 0 ? Vec3.atCenterOf(sample.block()).add(0, .485, 0) : null;
        }

        public PearlLandingPlanner.Step step(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
            if (wall && from.z < 13 && to.z >= 13) {
                Vec3 atWall = from.lerp(to, (13 - from.z) / (to.z - from.z));
                if (atWall.y <= 7) return PearlLandingPlanner.Step.BLOCKED;
            }
            if (from.y < 1 || to.y > 1) return PearlLandingPlanner.Step.OPEN;
            Vec3 hit = from.lerp(to, (1 - from.y) / (to.y - from.y));
            if (!abovePad(hit)) return PearlLandingPlanner.Step.OPEN;
            return new PearlLandingPlanner.Step(true, new PearlLandingPlanner.Impact(
                    hit, Direction.UP, BlockPos.containing(hit.add(0, -.01, 0))));
        }

        private boolean abovePad(Vec3 p) { return p.z >= 3 && p.z <= 34 && Math.abs(p.x - .5) < 4; }
        public boolean safeLanding(Vec3 p) { return abovePad(p) && p.y >= 1 && p.y < 3.5; }

        PearlLandingPlanner.Search search() {
            return new PearlLandingPlanner.Search(this, EYE, Vec3.atCenterOf(FAR), 32, PEARL);
        }
    }

    @Test void liveLookCanThrowBeforeAnyInverseTargetWasFound() {
        var world = new Platform(false);
        var s = world.search();
        var straight = s.advance(EYE, Vec3.ZERO, 0, 0, 120, 60, null);
        assertNotNull(straight);
        assertEquals(0F, straight.pitch());
        assertEquals(straight.flightTicks(), straight.score());
        assertEquals(1, straight.hitPosition().y, 1e-8);
        assertNotEquals(straight.hitPosition(), straight.landing());
        assertTrue(world.safeLanding(straight.landing()));
        var changed = s.advance(EYE, new Vec3(0, 0, .1), 0, 0, 120, 60, straight);
        assertNotNull(changed);
        assertTrue(changed.hitPosition().z > straight.hitPosition().z + 1);
        assertNull(s.advance(EYE, Vec3.ZERO, 0, 0, (int) straight.flightTicks() + 4, 60, straight));
    }

    @Test void sameLandingHasFastOpenThrowAndSafeLoftOverWall() {
        var open = new Platform(false, FAR).search().advance(EYE, Vec3.ZERO, 180, 35, 150, 60, null);
        var walled = new Platform(true, FAR).search().advance(EYE, Vec3.ZERO, 180, 35, 150, 60, null);
        assertNotNull(open);
        assertNotNull(walled);
        assertTrue(walled.pitch() < open.pitch() - 40);
        assertTrue(open.flightTicks() < 25);
        assertTrue(walled.flightTicks() > open.flightTicks() + 45);
        assertEquals(open.support(), walled.support());
    }

    @Test void aLaterNearThrowCanReplaceAnAlreadyVerifiedLoft() {
        var search = new Platform(true, FAR, NEAR).search();
        var plan = search.advance(EYE, Vec3.ZERO, 180, 35, 150, 15, null);
        assertNotNull(plan);
        assertTrue(plan.flightTicks() > 60);
        double firstEta = plan.score();
        for (int tick = 1; tick < 60 && plan.flightTicks() > 60; tick++) {
            plan = search.advance(EYE, Vec3.ZERO, 170, 30, 150 - tick, 15, plan);
            assertNotNull(plan);
        }
        assertEquals(NEAR, plan.support());
        assertTrue(plan.flightTicks() < 12);
        assertTrue(plan.score() < firstEta);
    }

    @Test void nearingAlignmentUpdatesTheIncumbentEtaWithoutRestartingItsTurn() {
        var search = new Platform(true, FAR).search();
        var initial = search.advance(EYE, Vec3.ZERO, 180, 35, 150, 15, null);
        assertNotNull(initial);
        var aligned = search.advance(EYE, Vec3.ZERO, initial.yaw(), initial.pitch(), 135, 15, initial);
        assertNotNull(aligned);
        assertEquals(initial.support(), aligned.support());
        assertEquals(initial.flightTicks(), aligned.score());
        assertTrue(initial.score() > aligned.score());
        assertEquals(1, PearlLandingPlanner.turnTicks(179, 0, -179, 0, 10));
    }
}
