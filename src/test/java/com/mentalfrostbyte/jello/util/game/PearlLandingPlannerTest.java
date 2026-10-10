package com.mentalfrostbyte.jello.util.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
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

    @Test void robustSearchCapsTheNumberOfCandidatesEvenWhenAValidOneExistsLater() {
        var launches = new AtomicInteger();
        var world = new Floor(0) {
            @Override public boolean clearSegment(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
                if (tick == 1) launches.incrementAndGet();
                return shot.origin().x >= PearlLandingPlanner.MAX_ROBUST_CANDIDATES;
            }
        };
        List<PearlLandingPlanner.RankedPlan> ranked = new ArrayList<>();
        for (int i = 10; i >= 0; i--) {
            Vec3 origin = new Vec3(i, 3, 0);
            var plan = new PearlLandingPlanner.Plan(0, 0, origin, Vec3.ZERO, BlockPos.ZERO, List.of(origin), 10, i);
            ranked.add(new PearlLandingPlanner.RankedPlan(plan, shot(origin, new Vec3(0, -0.2, 0))));
        }
        assertNull(PearlLandingPlanner.firstRobust(ranked, world));
        assertEquals(PearlLandingPlanner.MAX_ROBUST_CANDIDATES, launches.get());
    }

    @Test void robustSearchSkipsAnUnsafeShortCandidateAndReturnsTheNextSafeOne() {
        var world = new Floor(0) {
            @Override public boolean safeLanding(Vec3 feet) {
                return super.safeLanding(feet) && (Math.abs(feet.x) < 0.14 || feet.x > 3);
            }
        };
        Vec3 near = new Vec3(0, 3, 0), wide = new Vec3(6, 3, 0);
        var first = new PearlLandingPlanner.Plan(0, 0, near, Vec3.ZERO, BlockPos.ZERO, List.of(near), 10, 1);
        var second = new PearlLandingPlanner.Plan(0, 0, wide, Vec3.ZERO, BlockPos.ZERO, List.of(wide), 11, 2);
        assertNotNull(PearlLandingPlanner.trace(shot(near, Vec3.ZERO), world));
        assertNotNull(PearlLandingPlanner.trace(shot(wide, Vec3.ZERO), world));
        var candidates = List.of(new PearlLandingPlanner.RankedPlan(second, shot(wide, Vec3.ZERO)),
                new PearlLandingPlanner.RankedPlan(first, shot(near, Vec3.ZERO)));
        assertSame(second, PearlLandingPlanner.firstRobust(candidates, world));
    }

    private static Floor floor(double y) { return new Floor(y); }

    private static class Floor implements PearlLandingPlanner.FlightWorld {
        private final double y;
        Floor(double y) { this.y = y; }
        @Override public boolean clearSegment(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) { return true; }
        @Override public PearlLandingPlanner.Impact impact(Vec3 from, Vec3 to) {
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
