package com.mentalfrostbyte.jello.util.game;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TrajectoryWorldTest {
    private static final AABB BOX = new AABB(-1, -1, -1, 1, 1, 1);

    @Test
    void startingInsideAnEntityReportsTheEntryPointRatherThanTheExit() {
        Vec3 inside = new Vec3(0.25, 0.5, -0.25);
        assertEquals(inside, TrajectoryWorld.segmentBox(BOX, inside, new Vec3(10, 0.5, -0.25)));
        assertEquals(inside, TrajectoryWorld.segmentBox(BOX, inside, inside));
    }

    @Test
    void intersectionUsesTheNearestFaceInBothTravelDirections() {
        assertEquals(new Vec3(-1, 0, 0), TrajectoryWorld.segmentBox(BOX, new Vec3(-4, 0, 0), new Vec3(4, 0, 0)));
        assertEquals(new Vec3(1, 0, 0), TrajectoryWorld.segmentBox(BOX, new Vec3(4, 0, 0), new Vec3(-4, 0, 0)));
        assertEquals(new Vec3(-1, -1, -1), TrajectoryWorld.segmentBox(BOX, new Vec3(-2, -2, -2), new Vec3(2, 2, 2)));
    }

    @Test
    void aShortenedSegmentCannotHitAnEntityBeyondTheBlockingWall() {
        AABB behindWall = BOX.move(0, 0, 5);
        assertNull(TrajectoryWorld.segmentBox(behindWall, Vec3.ZERO, new Vec3(0, 0, 3)));
        assertNull(TrajectoryWorld.segmentBox(BOX, new Vec3(-4, 2, 0), new Vec3(4, 2, 0)));
        assertNull(TrajectoryWorld.segmentBox(BOX, new Vec3(4, 0, 0), new Vec3(5, 0, 0)));
    }

    @Test
    void touchingTheEntryFaceIsNotMissedAtZeroDistance() {
        Vec3 face = new Vec3(1, 0, 0);
        assertEquals(face, TrajectoryWorld.segmentBox(BOX, face, Vec3.ZERO));
        assertEquals(new Vec3(-1, 1, 0), TrajectoryWorld.segmentBox(BOX, new Vec3(-3, 1, 0), new Vec3(3, 1, 0)));
    }

    @Test
    void worldBorderCoordinatesRemainAccurateAndInvalidPositionsAreRejected() {
        Vec3 offset = new Vec3(29_999_980.125, 320, -29_999_980.125);
        Vec3 hit = TrajectoryWorld.segmentBox(BOX.move(offset), offset.add(-4, 0, 0), offset.add(4, 0, 0));
        assertNotNull(hit);
        assertEquals(offset.x - 1, hit.x, 1.0E-8);
        assertEquals(offset.y, hit.y);
        assertEquals(offset.z, hit.z);
        assertNull(TrajectoryWorld.segmentBox(BOX, new Vec3(Double.NaN, 0, 0), Vec3.ZERO));
        assertNull(TrajectoryWorld.segmentBox(BOX, Vec3.ZERO, new Vec3(Double.POSITIVE_INFINITY, 0, 0)));
    }

    @Test
    void entityMarginMatchesNewbornProjectileAgesAndStopsGrowing() {
        assertEquals(0F, TrajectoryWorld.entityMargin(0));
        assertEquals(0F, TrajectoryWorld.entityMargin(1));
        assertEquals(0.05F, TrajectoryWorld.entityMargin(2));
        assertEquals(0.3F, TrajectoryWorld.entityMargin(7));
        assertEquals(0.3F, TrajectoryWorld.entityMargin(160));
        assertEquals(0F, TrajectoryWorld.entityMargin(Integer.MIN_VALUE));
    }

    @Test
    void anArrowUsesTheActualBoxBeforeItsExpandedMargin() {
        Vec3 result = TrajectoryWorld.arrowIntersection(BOX, new Vec3(-3, 0, 0), new Vec3(3, 0, 0), 0.3F,
                (from, to) -> { throw new AssertionError("A direct hit needs no sideways block query"); });
        assertEquals(new Vec3(-1, 0, 0), result);
    }

    @Test
    void anArrowGrazingTheMarginEndsAtTheVisibleEntitySurface() {
        Vec3 from = new Vec3(-3, 1.2, 0);
        Vec3 to = new Vec3(3, 1.2, 0);
        assertNull(TrajectoryWorld.segmentBox(BOX, from, to));
        Vec3 result = TrajectoryWorld.arrowIntersection(BOX, from, to, 0.3F, (outside, center) -> center);
        assertNotNull(result);
        assertEquals(-1, result.x, 1.0E-7);
        assertTrue(result.y <= 1 && result.y >= -1);
    }

    @Test
    void anArrowMarginCannotClaimAnEntityBehindAWallCornerOrUnloadedTerrain() {
        Vec3 from = new Vec3(-3, 1.2, 0);
        Vec3 to = new Vec3(3, 1.2, 0);
        assertNotNull(TrajectoryWorld.segmentBox(BOX.inflate(0.3), from, to));
        assertNull(TrajectoryWorld.arrowIntersection(BOX, from, to, 0.3F,
                (outside, center) -> outside.lerp(center, 0.1)));
        assertNull(TrajectoryWorld.arrowIntersection(BOX, from, to, 0.3F, (outside, center) -> null));
    }
}
