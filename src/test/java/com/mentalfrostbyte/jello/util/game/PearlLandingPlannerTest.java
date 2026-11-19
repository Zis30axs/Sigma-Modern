package com.mentalfrostbyte.jello.util.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PearlLandingPlannerTest {
    @Test void landingIsTheCollisionTicksOldPositionRatherThanTheHitPoint() {
        var flight = PearlLandingPlanner.trace(shot(new Vec3(0, 3, 0), new Vec3(0.3, -0.2, 0)), floor(0));
        assertNotNull(flight);
        assertTrue(flight.landing().y > 0);
        assertEquals(0, flight.impact().position().y, 1.0E-8);
        assertEquals(flight.path().get(flight.path().size() - 2), flight.landing());
        assertNotEquals(flight.landing(), flight.impact().position());
        assertThrows(UnsupportedOperationException.class, () -> flight.path().clear());
    }

    @Test void aWallCollisionIsRejectedEvenIfThereIsSafeGroundLater() {
        var world = new Floor(0) {
            @Override public PearlLandingPlanner.Impact impact(Vec3 from, Vec3 to) {
                return new PearlLandingPlanner.Impact(from.lerp(to, 0.5), Direction.WEST, new BlockPos(1, 1, 0));
            }
        };
        assertNull(PearlLandingPlanner.trace(shot(new Vec3(0, 3, 0), new Vec3(1, -0.2, 0)), world));
    }

    @Test void aTopHitDoesNotRescueAnUnsupportedOldPositionBesideAPlatform() {
        var world = new Floor(0) {
            @Override public boolean safeLanding(Vec3 feet) { return false; }
        };
        assertNull(PearlLandingPlanner.trace(shot(new Vec3(-0.7, 0.5, 0), new Vec3(1, -0.7, 0)), world));
    }

    @Test void ascendingTopHitsAreRejectedRatherThanMistakenForLanding() {
        var world = new Floor(0) {
            @Override public PearlLandingPlanner.Impact impact(Vec3 from, Vec3 to) {
                return new PearlLandingPlanner.Impact(to, Direction.UP, BlockPos.ZERO);
            }
        };
        assertNull(PearlLandingPlanner.trace(shot(new Vec3(0, 3, 0), new Vec3(0, 1, 0)), world));
    }

    @Test void unloadedSegmentsFluidAndEntityBlockersStopTheSearch() {
        var calls = new AtomicInteger();
        var world = new Floor(0) {
            @Override public boolean clearSegment(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
                calls.incrementAndGet();
                return false;
            }
            @Override public PearlLandingPlanner.Impact impact(Vec3 from, Vec3 to) {
                fail("Do not read collisions after an unloaded or unsafe segment");
                return null;
            }
        };
        assertNull(PearlLandingPlanner.trace(shot(new Vec3(0, 3, 0), new Vec3(0.2, 0.2, 0)), world));
        assertEquals(1, calls.get());
    }

    @Test void simulationContinuesPastAnAscendingSolverEndpointUntilRealImpact() {
        var template = new ProjectileLaunch.Template(1.5F, 0, ProjectileLaunch.Physics.THROWABLE,
                0.03, 0.99F, 0.8F, -0.1F, 0.25F, 0.25F, 160, true);
        var aim = ProjectileAim.solve(new ProjectileAim.Input(template, new Vec3(0, 1, 0),
                Vec3.ZERO, new Vec3(0, 5, 5), Vec3.ZERO));
        assertNotNull(aim);
        assertTrue(aim.path().getLast().y > aim.path().get(aim.path().size() - 2).y);
        var flight = PearlLandingPlanner.trace(aim.shot(), floor(5));
        assertNotNull(flight);
        assertTrue(flight.ticks() > aim.timeTicks() + 5);
        assertTrue(flight.impact().position().z > aim.hitPoint().z + 2);
    }

    @Test void anEmptyWorldCannotCreateAnUnboundedSearch() {
        var calls = new AtomicInteger();
        var world = new Floor(-100_000) {
            @Override public boolean clearSegment(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
                calls.incrementAndGet();
                return true;
            }
        };
        assertNull(PearlLandingPlanner.trace(shot(new Vec3(0, 3, 0), Vec3.ZERO), world));
        assertTrue(calls.get() <= 160);
    }

    @Test void invalidCoordinatesNeverReachWorldQueries() {
        assertNull(PearlLandingPlanner.trace(shot(new Vec3(Double.NaN, 3, 0), Vec3.ZERO), floor(0)));
        assertNull(PearlLandingPlanner.trace(shot(Vec3.ZERO, new Vec3(Double.POSITIVE_INFINITY, 0, 0)), floor(0)));
    }

    @Test void aWidePlatformAcceptsAllEightRealVelocitySpreadCorners() {
        Set<Vec3> launches = new HashSet<>();
        var world = new Floor(0) {
            @Override public boolean clearSegment(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
                launches.add(shot.velocity());
                return true;
            }
        };
        var shot = shot(new Vec3(0, 6, 0), new Vec3(0, 0.2, 0.8));
        assertNotNull(PearlLandingPlanner.trace(shot, world));
        launches.clear();
        assertTrue(PearlLandingPlanner.spreadSafe(shot, world));
        assertEquals(8, launches.size());
        assertEquals(0.02584125, PearlLandingPlanner.PEARL_VELOCITY_SPREAD, 1.0E-12);
        for (Vec3 velocity : launches) {
            Vec3 deviation = velocity.subtract(shot.velocity());
            assertEquals(PearlLandingPlanner.PEARL_VELOCITY_SPREAD, Math.abs(deviation.x), 1.0E-12);
            assertEquals(PearlLandingPlanner.PEARL_VELOCITY_SPREAD, Math.abs(deviation.y), 1.0E-12);
            assertEquals(PearlLandingPlanner.PEARL_VELOCITY_SPREAD, Math.abs(deviation.z), 1.0E-12);
        }
    }

    @Test void nominallySafeNarrowLedgeIsRejectedWhenSpreadWouldLandBesideIt() {
        var world = new Floor(0) {
            @Override public boolean safeLanding(Vec3 feet) {
                return super.safeLanding(feet) && Math.abs(feet.x) < 0.14;
            }
        };
        var shot = shot(new Vec3(0, 6, 0), new Vec3(0, 0.2, 0.8));
        assertNotNull(PearlLandingPlanner.trace(shot, world));
        assertFalse(PearlLandingPlanner.spreadSafe(shot, world));
    }

    @Test void progressiveScanVisitsEveryIntersectingColumnExactlyOnce() {
        for (double radius : new double[] {8, 24, 48}) {
            Vec3 eye = new Vec3(-10.83, 72, 19.94), lastGround = eye.add(3, -5, 4);
            var columns = PearlLandingPlanner.columns(eye, lastGround, radius);
            var expected = new HashSet<PearlLandingPlanner.Column>();
            for (int x = -62; x <= 40; x++) {
                for (int z = -31; z <= 70; z++) {
                    double dx = Math.max(0, Math.max(x - eye.x, eye.x - (x + 1)));
                    double dz = Math.max(0, Math.max(z - eye.z, eye.z - (z + 1)));
                    if (dx * dx + dz * dz <= radius * radius) expected.add(new PearlLandingPlanner.Column(x, z));
                }
            }
            assertEquals(expected, new HashSet<>(columns));
            assertEquals(expected.size(), columns.size());
            assertEquals(new PearlLandingPlanner.Column(-8, 23), columns.getFirst());
            assertTrue(columns.subList(0, Math.min(128, columns.size())).stream().anyMatch(c ->
                    Math.hypot(c.x() - eye.x, c.z() - eye.z) >= radius - 2));
        }
    }

    @Test void stationarySolverMatchesDiscretePearlPhysicsInAllOctantsAtMultipleHeights() {
        var template = new ProjectileLaunch.Template(1.5F, 0, ProjectileLaunch.Physics.THROWABLE,
                0.03, 0.99F, 0.8F, -0.1F, 0.25F, 0.25F, 160, true);
        Vec3 eye = new Vec3(0.3, 80, -0.2), inherited = new Vec3(.11, -.06, -.08);
        var solver = new ProjectileAim.StationarySolver(template, eye, inherited);
        for (int angle = 0; angle < 360; angle += 15) {
            for (int y : new int[] {-55, -6, 0, 9}) {
                Vec3 point = eye.add(20 * Math.sin(Math.toRadians(angle)), y, 20 * Math.cos(Math.toRadians(angle)));
                var reference = ProjectileAim.solveAll(new ProjectileAim.Input(template, eye, inherited, point, Vec3.ZERO));
                var arcs = solver.solve(point, 160);
                assertFalse(arcs.isEmpty());
                assertEquals(reference.size(), arcs.size());
                for (int arc = 0; arc < arcs.size(); arc++) {
                    assertEquals(reference.get(arc).timeTicks(), arcs.get(arc).timeTicks(), 1.0E-6);
                    var launch = arcs.get(arc);
                    Vec3 pos = launch.shot().origin(), velocity = launch.shot().velocity();
                    for (int t = 0; t < launch.timeTicks(); t++) {
                        velocity = velocity.add(0, -template.gravity(), 0).scale(template.airDrag());
                        pos = pos.add(velocity.scale(Math.min(1, launch.timeTicks() - t)));
                    }
                    assertTrue(pos.distanceTo(point) < .05);
                }
                assertTrue(solver.solve(point, (int) arcs.getFirst().timeTicks() - 1).isEmpty());
            }
        }
    }

    @Test void verticalThrowsAndUnreachableTargets() {
        var template = new ProjectileLaunch.Template(1.5F, 0, ProjectileLaunch.Physics.THROWABLE,
                0.03, 0.99F, 0.8F, -0.1F, 0.25F, 0.25F, 160, true);
        var solver = new ProjectileAim.StationarySolver(template, Vec3.ZERO, Vec3.ZERO);
        assertFalse(solver.solve(new Vec3(0, -60, 0), 160).isEmpty());
        assertFalse(solver.solve(new Vec3(0, 18, 0), 160).isEmpty());
        assertTrue(solver.solve(new Vec3(0, solver.highestY() + 1, 0), 160).isEmpty());
    }

    @Test void scansCaveFloorsBelowARoofAndSkipsEmptySectionsAndBuriedBlocks() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var palette = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        var roof = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), palette);
        var cave = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), palette);
        roof.set(1, 0, 3, Blocks.STONE.defaultBlockState());
        cave.set(1, 9, 3, Blocks.STONE.defaultBlockState());
        cave.set(1, 8, 3, Blocks.STONE.defaultBlockState());
        cave.set(1, 4, 3, Blocks.MAGMA_BLOCK.defaultBlockState());
        cave.set(1, 1, 3, Blocks.STONE_SLAB.defaultBlockState());
        var surfaces = new ArrayList<BlockPos>();
        var sections = new AtomicInteger();
        PearlLandingPlanner.scanSurfaces(EmptyBlockGetter.INSTANCE, new PearlLandingPlanner.Column(-15, 3),
                -32, 38, y -> {
                    sections.incrementAndGet();
                    return switch (y >> 4) { case 2 -> roof; case -2 -> cave; default -> null; };
                }, surfaces::add);
        assertEquals(5, sections.get());
        assertEquals(List.of(new BlockPos(-15, 32, 3), new BlockPos(-15, -23, 3),
                new BlockPos(-15, -31, 3)), surfaces);
    }

    @Test void cursorResumesUntilItReachesAnOuterColumnWellBelowTheOldHeightCutoff() {
        Vec3 eye = new Vec3(.5, 64, .5);
        var template = new ProjectileLaunch.Template(1.5F, 0, ProjectileLaunch.Physics.THROWABLE,
                .03, .99F, .8F, -.1F, .25F, .25F, 160, true);
        var pad = new BlockPos(-31, 13, 25);
        Set<PearlLandingPlanner.Column> seen = new HashSet<>();
        var world = new PearlLandingPlanner.SearchWorld() {
            public void scanColumn(PearlLandingPlanner.Column c, int min, int max, Consumer<BlockPos> emit) {
                assertTrue(seen.add(c), "a resumed slice repeated a column");
                if (c.x() == pad.getX() && c.z() == pad.getZ() && min <= pad.getY() && max >= pad.getY()) emit.accept(pad);
            }
            public Vec3 target(PearlLandingPlanner.Sample sample, Vec3 origin, double range) {
                return Vec3.atCenterOf(pad).add(0, .485, 0);
            }
            public boolean safeLanding(Vec3 feet) {
                return Math.abs(feet.x - pad.getX() - .5) < 4 && Math.abs(feet.z - pad.getZ() - .5) < 4
                        && feet.y >= 14 && feet.y < 16.5;
            }
            public PearlLandingPlanner.Step step(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
                if (from.y < 14 || to.y >= 14) return PearlLandingPlanner.Step.OPEN;
                Vec3 at = from.lerp(to, (from.y - 14) / (from.y - to.y));
                if (Math.abs(at.x - pad.getX() - .5) >= 4 || Math.abs(at.z - pad.getZ() - .5) >= 4)
                    return PearlLandingPlanner.Step.OPEN;
                return new PearlLandingPlanner.Step(true, new PearlLandingPlanner.Impact(at, Direction.UP,
                        BlockPos.containing(at.add(0, -.01, 0))));
            }
        };
        var search = new PearlLandingPlanner.Search(world, eye, null, 48, template);
        PearlLandingPlanner.Plan found = null;
        int slices = 0;
        while (!search.exhausted() && found == null && slices++ < 200) {
            found = search.advance(eye, Vec3.ZERO, -150, 70, 200 - slices, 60);
        }
        assertTrue(slices > 1);
        assertNotNull(found);
        assertTrue(world.safeLanding(found.landing()));
    }

    @Test void exhaustedSearchKeepsTryingLaterSurfacesAndSubBlockTargets() {
        Vec3 eye = new Vec3(.5, 5, .5);
        var template = new ProjectileLaunch.Template(1.5F, 0, ProjectileLaunch.Physics.THROWABLE,
                .03, .99F, .8F, -.1F, .25F, .25F, 160, true);
        Set<PearlLandingPlanner.Sample> samples = new HashSet<>();
        var world = new PearlLandingPlanner.SearchWorld() {
            public void scanColumn(PearlLandingPlanner.Column c, int min, int max, Consumer<BlockPos> emit) {
                emit.accept(new BlockPos(c.x(), 0, c.z()));
            }
            public Vec3 target(PearlLandingPlanner.Sample sample, Vec3 origin, double range) {
                assertTrue(samples.add(sample));
                return null;
            }
            public PearlLandingPlanner.Step step(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
                return PearlLandingPlanner.Step.BLOCKED;
            }
            public boolean safeLanding(Vec3 feet) { throw new AssertionError(); }
        };
        var search = new PearlLandingPlanner.Search(world, eye, null, 8, template);
        int limit = 1000;
        while (!search.exhausted() && limit-- > 0) assertNull(search.advance(eye, Vec3.ZERO, 0, 0, 160, 60));
        assertTrue(search.exhausted());
        assertEquals(9 * PearlLandingPlanner.columns(eye, null, 8).size(), samples.size());
    }

    private static Floor floor(double y) { return new Floor(y); }

    private static class Floor implements PearlLandingPlanner.FlightWorld {
        private final double y;
        Floor(double y) { this.y = y; }
        @Override public PearlLandingPlanner.Step step(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
            return clearSegment(from, to, shot, tick) ? new PearlLandingPlanner.Step(true, impact(from, to))
                    : PearlLandingPlanner.Step.BLOCKED;
        }
        public boolean clearSegment(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) { return true; }
        public PearlLandingPlanner.Impact impact(Vec3 from, Vec3 to) {
            if (from.y < this.y || to.y >= this.y) return null;
            Vec3 location = from.lerp(to, (from.y - this.y) / (from.y - to.y));
            return new PearlLandingPlanner.Impact(location, Direction.UP, BlockPos.containing(location.add(0, -0.01, 0)));
        }
        @Override public boolean safeLanding(Vec3 feet) { return feet.y >= this.y && feet.y - this.y <= 2.5; }
    }

    private static ProjectileLaunch.Shot shot(Vec3 origin, Vec3 velocity) {
        return new ProjectileLaunch.Shot(origin, velocity, ProjectileLaunch.Physics.THROWABLE,
                0.03, 0.99F, 0.8F, 0.25F, 0.25F, 160, false);
    }
}
