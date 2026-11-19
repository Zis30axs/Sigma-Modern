package com.mentalfrostbyte.jello.util.game;

import static com.mentalfrostbyte.jello.util.game.ProjectileDefensePlanner.Action.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mentalfrostbyte.jello.util.game.ProjectileDefensePlanner.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProjectileDefensePlannerTest {
    private static final AABB PLAYER = new AABB(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
    private static final BiFunction<Vec3, Vec3, Vec3> CLEAR = (from, to) -> to;
    private static final int TICKS = 9;

    private static Flight shot(double x, double z, boolean blockable) {
        var path = new ArrayList<Segment>();
        for (int tick = 0; tick < TICKS; tick++) {
            Vec3 from = new Vec3(x, 1, z - tick);
            path.add(new Segment(from, from.add(0, 0, -1), 1, 0, false));
        }
        return new Flight(path, blockable);
    }

    private static Walk walk(Vec2 input, float speed) {
        var boxes = new ArrayList<AABB>();
        for (int tick = 0; tick <= TICKS; tick++) {
            boxes.add(PLAYER.move(input.x * 0.22 * speed * tick, 0, input.y * 0.22 * speed * tick));
        }
        return new Walk(boxes, false);
    }

    private static Plan plan(List<Flight> flights, Vec2 input, int delay, Walker walker) {
        return ProjectileDefensePlanner.choose(flights, input, 0, delay, 0.2F, walker, CLEAR);
    }

    @Test void noProjectilesDoesNotSimulateThePlayer() {
        var input = new Vec2(0.2F, -0.3F);
        Plan result = plan(List.of(), input, 0, (v, s) -> { throw new AssertionError(); });
        assertEquals(KEEP, result.action());
        assertSame(input, result.input());
    }

    @Test void alreadyEscapingOrMissedArrowsPreserveInput() {
        assertEquals(KEEP, plan(List.of(shot(0, 6, true)), new Vec2(1, 0), 0, ProjectileDefensePlannerTest::walk).action());
        assertEquals(KEEP, plan(List.of(shot(1, 6, true)), Vec2.ZERO, 0, ProjectileDefensePlannerTest::walk).action());
    }

    @Test void shieldsOnlyIfReadyBeforeImpactAndAllThreatsAreCovered() {
        assertEquals(BLOCK, plan(List.of(shot(0, 6, true)), Vec2.ZERO, 5, ProjectileDefensePlannerTest::walk).action());
        assertEquals(DODGE, plan(List.of(shot(0, 5, true)), Vec2.ZERO, 5, ProjectileDefensePlannerTest::walk).action());
        assertEquals(DODGE, plan(List.of(shot(0, 6, false)), Vec2.ZERO, 0, ProjectileDefensePlannerTest::walk).action());
        assertEquals(DODGE, plan(List.of(shot(0, 6, true), shot(0, 7, false)), Vec2.ZERO, 0, ProjectileDefensePlannerTest::walk).action());
    }

    @Test void doesNotRaiseAShieldAgainstAPiercerJustToSlowMovement() {
        Plan result = plan(List.of(shot(1.32, 6, false)), new Vec2(1, 0), 0, ProjectileDefensePlannerTest::walk);
        assertEquals(DODGE, result.action());
    }

    @Test void blockingChecksWhereTheSlowerPlayerWillBe() {
        // At t=6 the fast walk is at x=1.32; blocking puts the player at x=0.264 in the piercing arrow's lane.
        Plan result = plan(List.of(shot(1.32, 6, true), shot(0.26, 6, false)), new Vec2(1, 0), 0,
                ProjectileDefensePlannerTest::walk);
        assertEquals(DODGE, result.action());
    }

    @Test void dodgeAccountsForEveryArrowAndRejectsAnUnsupportedDirection() {
        var flights = List.of(shot(0, 6, false), shot(1.32, 6, false));
        Plan againstVolley = plan(flights, Vec2.ZERO, -1, ProjectileDefensePlannerTest::walk);
        assertEquals(DODGE, againstVolley.action());
        assertTrue(againstVolley.input().x < 0);
        Plan atEdge = plan(List.of(shot(0, 6, false)), Vec2.ZERO, -1, (input, speed) ->
                new Walk(walk(input, speed).boxes(), input.x > 0));
        assertEquals(DODGE, atEdge.action());
        assertTrue(atEdge.input().x < 0);
        Plan boxedIn = plan(flights, Vec2.ZERO, -1, (input, speed) -> walk(Vec2.ZERO, speed));
        assertEquals(KEEP, boxedIn.action());
    }

    @Test void movingBoxIntersectsDuringTheTickAndStopsAtWallTime() {
        AABB left = PLAYER.move(-1, 0, 0);
        AABB right = PLAYER.move(1, 0, 0);
        Segment crossing = new Segment(new Vec3(0, 1, -1), new Vec3(0, 1, 1), 1, 0, false);
        assertEquals(0.35, ProjectileDefensePlanner.crossing(left, right, crossing, CLEAR), 1.0E-8);
        Segment wall = new Segment(new Vec3(0, 1, -1), new Vec3(0, 1, -0.5), 0.25, 0, true);
        assertFalse(Double.isFinite(ProjectileDefensePlanner.crossing(left, right, wall, CLEAR)));
        Segment hitsWallAtBox = new Segment(new Vec3(0, 1, -1), new Vec3(0, 1, -0.3), 0.35, 0, true);
        assertFalse(Double.isFinite(ProjectileDefensePlanner.crossing(PLAYER, PLAYER, hitsWallAtBox, CLEAR)));
    }

    @Test void aPaddedBoxCannotReachThroughCover() {
        Segment graze = new Segment(new Vec3(0.41, 1, -1), new Vec3(0.41, 1, 1), 1, 0.2F, false);
        assertTrue(Double.isFinite(ProjectileDefensePlanner.crossing(PLAYER, PLAYER, graze, CLEAR)));
        assertFalse(Double.isFinite(ProjectileDefensePlanner.crossing(PLAYER, PLAYER, graze, (from, to) -> from)));
    }

    @Test void inputRotationMatchesMinecraftYaw() {
        Vec3 west = ProjectileDefensePlanner.heading(new Vec2(0, 1), 90);
        assertEquals(-1, west.x, 1.0E-5);
        assertEquals(0, west.z, 1.0E-5);
    }
}
