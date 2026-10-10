package com.mentalfrostbyte.jello.util.game;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProjectileTrajectoriesTest {
    private static class EmptyWorld implements ProjectileTrajectories.Environment {
        @Override public boolean loaded(Vec3 from, Vec3 to) { return true; }
        @Override public boolean water(Vec3 position, ProjectileLaunch.Shot shot) { return false; }
        @Override public ProjectileTrajectories.Hit collision(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) { return null; }
    }

    @Test void arrowMovesBeforeApplyingAirDragAndGravity() {
        var path = ProjectileTrajectories.simulate(shot(ProjectileLaunch.Physics.ARROW, 0.05, 2), new EmptyWorld(), 64);
        assertPoint(new Vec3(0, 10, 2), path.points().get(1));
        assertPoint(new Vec3(0, 9.95, 3.98), path.points().get(2));
        assertNull(path.hitPosition());
    }

    @Test void thrownItemsApplyGravityThenDragBeforeTheirFirstMovement() {
        var path = ProjectileTrajectories.simulate(shot(ProjectileLaunch.Physics.THROWABLE, 0.03, 2), new EmptyWorld(), 64);
        assertPoint(new Vec3(0, 9.9703, 1.98), path.points().get(1));
        assertPoint(new Vec3(0, 9.911197, 3.9402), path.points().get(2));
    }

    @Test void eachTickResamplesWaterInsteadOfKeepingTheLaunchMedium() {
        var world = new EmptyWorld() {
            @Override public boolean water(Vec3 p, ProjectileLaunch.Shot shot) { return p.z >= 1; }
        };
        var path = ProjectileTrajectories.simulate(shot(ProjectileLaunch.Physics.ARROW, 0.05, 3), world, 64);
        assertPoint(new Vec3(0, 10, 2), path.points().get(1));
        assertPoint(new Vec3(0, 9.95, 3.98), path.points().get(2));
        assertPoint(new Vec3(0, 9.87, 5.168), path.points().get(3));
    }

    @Test void underwaterLaunchUsesAirOnItsFirstTickThenWaterBeforeMoving() {
        var original = shot(ProjectileLaunch.Physics.THROWABLE, 0.03, 1);
        var throwable = new ProjectileLaunch.Shot(original.origin(), original.velocity(), original.physics(),
                original.gravity(), original.airDrag(), 0.8, original.width(), original.height(), 2, false);
        var world = new EmptyWorld() {
            @Override public boolean water(Vec3 p, ProjectileLaunch.Shot shot) { return true; }
        };
        var path = ProjectileTrajectories.simulate(throwable, world, 64);
        assertPoint(new Vec3(0, 9.9703, 1.98), path.points().get(1));
        assertPoint(new Vec3(0, 9.92254, 3.564), path.points().get(2));
    }

    @Test void windChargesAndAngledFireworksKeepStraightFlight() {
        for (var physics : new ProjectileLaunch.Physics[] {ProjectileLaunch.Physics.WIND_CHARGE, ProjectileLaunch.Physics.FIREWORK}) {
            var shot = new ProjectileLaunch.Shot(new Vec3(1, 10, 1), new Vec3(0.5, 0.25, 2), physics,
                    0, 1, 1, 0.25F, 0.25F, 3, false);
            assertPoint(new Vec3(2.5, 10.75, 7), ProjectileTrajectories.simulate(shot, new EmptyWorld(), 64).points().getLast());
        }
    }

    @Test void collisionStopsAtTheExactHitAndKeepsTheBlockFace() {
        var world = new EmptyWorld() {
            @Override public ProjectileTrajectories.Hit collision(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
                return new ProjectileTrajectories.Hit(from.lerp(to, 0.25), Direction.NORTH, false);
            }
        };
        var path = ProjectileTrajectories.simulate(shot(ProjectileLaunch.Physics.ARROW, 0.05, 200), world, 128);
        assertEquals(2, path.points().size());
        assertPoint(new Vec3(0, 10, 0.5), path.hitPosition());
        assertEquals(Direction.NORTH, path.hitFace());
        assertFalse(path.entityHit());
        assertEquals(path.hitPosition(), path.points().getLast());
    }

    @Test void anEntityHitEndsThePreviewEvenForAProjectileThatCouldLaterDeflectOrPierce() {
        var world = new EmptyWorld() {
            @Override public ProjectileTrajectories.Hit collision(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
                return tick == 1 ? new ProjectileTrajectories.Hit(from.lerp(to, 0.5), null, true) : null;
            }
        };
        var path = ProjectileTrajectories.simulate(shot(ProjectileLaunch.Physics.ARROW, 0.05, 200), world, 128);
        assertEquals(3, path.points().size());
        assertTrue(path.entityHit());
        assertNull(path.hitFace());
    }

    @Test void rangeEndIsClippedWithoutInventingAnImpact() {
        var path = ProjectileTrajectories.simulate(shot(ProjectileLaunch.Physics.ARROW, 0, 200), new EmptyWorld(), 3);
        assertPoint(new Vec3(0, 10, 3), path.points().getLast());
        assertNull(path.hitPosition());
        assertFalse(path.entityHit());
        assertEquals(3, path.points().size());
    }

    @Test void unloadedTerrainStopsBeforeAnyCollisionOrFluidQueryThere() {
        var world = new EmptyWorld() {
            @Override public boolean loaded(Vec3 from, Vec3 to) { return to.z <= 2; }
            @Override public ProjectileTrajectories.Hit collision(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) {
                assertTrue(to.z <= 2);
                return null;
            }
        };
        var path = ProjectileTrajectories.simulate(shot(ProjectileLaunch.Physics.ARROW, 0, 200), world, 128);
        assertEquals(2, path.points().size());
        assertNull(path.hitPosition());
    }

    @Test void simulationIsBoundedAndReturnedPointsAreImmutable() {
        var shot = new ProjectileLaunch.Shot(Vec3.ZERO, new Vec3(0, 0, 0.01), ProjectileLaunch.Physics.WIND_CHARGE,
                0, 1, 1, 0.25F, 0.25F, Integer.MAX_VALUE, true);
        var path = ProjectileTrajectories.simulate(shot, new EmptyWorld(), 1000);
        assertEquals(ProjectileTrajectories.MAX_TICKS + 1, path.points().size());
        assertTrue(path.preview());
        assertThrows(UnsupportedOperationException.class, () -> path.points().clear());
        assertNull(path.hitPosition());
    }

    @Test void invalidInputCannotReachWorldQueries() {
        var world = new EmptyWorld() {
            @Override public boolean loaded(Vec3 from, Vec3 to) { fail("invalid input queried the world"); return false; }
        };
        var valid = shot(ProjectileLaunch.Physics.ARROW, 0.05, 20);
        assertTrue(ProjectileTrajectories.simulate(valid, world, Double.NaN).points().isEmpty());
        assertTrue(ProjectileTrajectories.simulate(null, world, 128).points().isEmpty());
        var invalid = new ProjectileLaunch.Shot(new Vec3(Double.NaN, 0, 0), Vec3.ZERO, valid.physics(),
                0.05, 0.99, 0.6, 0.5F, 0.5F, 20, false);
        assertTrue(ProjectileTrajectories.simulate(invalid, world, 128).points().isEmpty());
    }

    private static ProjectileLaunch.Shot shot(ProjectileLaunch.Physics physics, double gravity, int ticks) {
        return new ProjectileLaunch.Shot(new Vec3(0, 10, 0), new Vec3(0, 0, 2), physics,
                gravity, 0.99, 0.6, 0.5F, 0.5F, ticks, false);
    }

    private static void assertPoint(Vec3 expected, Vec3 actual) {
        assertNotNull(actual);
        assertEquals(expected.x, actual.x, 1.0E-6);
        assertEquals(expected.y, actual.y, 1.0E-6);
        assertEquals(expected.z, actual.z, 1.0E-6);
    }
}
