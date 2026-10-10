package com.mentalfrostbyte.jello.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class RotationIndicatorGeometryTest {
    private static final float RADIUS = 48.0F;

    @Test void matchingDirectionAndSubHalfDegreeMotionStayAtTheCrosshair() {
        Matrix4f view = camera(0, 0);
        assertNull(RotationIndicatorGeometry.project(view, 0, 0, RADIUS));
        assertNull(RotationIndicatorGeometry.project(view, 0.49F, 0, RADIUS));
        assertNull(RotationIndicatorGeometry.project(view, 0, -0.49F, RADIUS));
        assertNotNull(RotationIndicatorGeometry.project(view, 0.51F, 0, RADIUS));
        assertNull(RotationIndicatorGeometry.project(camera(127, -34), 127, -34, RADIUS));
    }

    @Test void yawPointsToTheCorrectSideAndScalesWithTheAngularDifference() {
        var right = RotationIndicatorGeometry.project(camera(0, 0), 45, 0, RADIUS);
        var left = RotationIndicatorGeometry.project(camera(0, 0), -45, 0, RADIUS);
        assertNotNull(right);
        assertNotNull(left);
        assertEquals(RADIUS / 2, right.x(), 0.0001F);
        assertEquals(-RADIUS / 2, left.x(), 0.0001F);
        assertEquals(0, right.y(), 0.0001F);
        assertEquals(0, left.y(), 0.0001F);
        assertFalse(right.behind());
        assertFalse(left.behind());
    }

    @Test void positivePitchPointsDownAndNegativePitchPointsUp() {
        var down = RotationIndicatorGeometry.project(camera(0, 0), 0, 30, RADIUS);
        var up = RotationIndicatorGeometry.project(camera(0, 0), 0, -30, RADIUS);
        assertNotNull(down);
        assertNotNull(up);
        assertEquals(0, down.x(), 0.0001F);
        assertEquals(0, up.x(), 0.0001F);
        assertEquals(RADIUS / 3, down.y(), 0.0001F);
        assertEquals(-RADIUS / 3, up.y(), 0.0001F);
    }

    @Test void cameraYawAndPitchAreBothRemovedFromTheWorldDirection() {
        Matrix4f view = camera(120, 30);
        var down = RotationIndicatorGeometry.project(view, 120, 45, RADIUS);
        var right = RotationIndicatorGeometry.project(view, 150, 30, RADIUS);
        assertNotNull(down);
        assertNotNull(right);
        assertEquals(0, down.x(), 0.0001F);
        assertEquals(RADIUS / 6, down.y(), 0.0001F);
        assertTrue(right.x() > 0);
        assertFalse(right.behind());
        assertTrue(Math.hypot(right.x(), right.y()) < RADIUS / 3);
    }

    @Test void cameraRollUsesTheRenderedViewAxes() {
        Matrix4f rolledView = new Matrix4f().rotationZ((float) (Math.PI / 2)).mul(camera(0, 0));
        var pointer = RotationIndicatorGeometry.project(rolledView, 45, 0, RADIUS);
        assertNotNull(pointer);
        assertEquals(0, pointer.x(), 0.0001F);
        assertEquals(-RADIUS / 2, pointer.y(), 0.0001F);
    }

    @Test void yawWrapAndMultipleTurnsPreserveTheShortDirection() {
        var right = RotationIndicatorGeometry.project(camera(179, 0), -179, 0, RADIUS);
        var left = RotationIndicatorGeometry.project(camera(-179, 0), 179, 0, RADIUS);
        var wrappedRight = RotationIndicatorGeometry.project(camera(179, 0), 541, 0, RADIUS);
        var wrappedLeft = RotationIndicatorGeometry.project(camera(-179, 0), -541, 0, RADIUS);
        assertNotNull(right);
        assertNotNull(left);
        assertNotNull(wrappedRight);
        assertNotNull(wrappedLeft);
        assertEquals(RADIUS * 2 / 90, right.x(), 0.0001F);
        assertEquals(-RADIUS * 2 / 90, left.x(), 0.0001F);
        assertEquals(right.x(), wrappedRight.x(), 0.0001F);
        assertEquals(left.x(), wrappedLeft.x(), 0.0001F);
        assertNull(RotationIndicatorGeometry.project(camera(0, 0), 720, 0, RADIUS));
        assertNull(RotationIndicatorGeometry.project(camera(0, 0), -720, 0, RADIUS));
    }

    @Test void rearDirectionsKeepTheirSideWithoutPerspectiveInversion() {
        var right = RotationIndicatorGeometry.project(camera(0, 0), 150, 0, RADIUS);
        var left = RotationIndicatorGeometry.project(camera(0, 0), -150, 0, RADIUS);
        assertNotNull(right);
        assertNotNull(left);
        assertTrue(right.behind());
        assertTrue(left.behind());
        assertEquals(RADIUS, right.x(), 0.0001F);
        assertEquals(-RADIUS, left.x(), 0.0001F);
        var rearDown = RotationIndicatorGeometry.project(camera(0, 0), 150, 30, RADIUS);
        assertNotNull(rearDown);
        assertTrue(rearDown.x() > 0);
        assertTrue(rearDown.y() > 0);
        assertEquals(RADIUS, Math.hypot(rearDown.x(), rearDown.y()), 0.0001);
    }

    @Test void exactOppositeDirectionAlwaysUsesAStableRightCue() {
        for (float yaw : new float[]{180, -180, 540, -540}) {
            var pointer = RotationIndicatorGeometry.project(camera(0, 0), yaw, 0, RADIUS);
            assertNotNull(pointer);
            assertTrue(pointer.behind());
            assertEquals(RADIUS, pointer.x());
            assertEquals(0, pointer.y());
        }
        var rotated = RotationIndicatorGeometry.project(camera(63, 24), 243, -24, RADIUS);
        assertNotNull(rotated);
        assertTrue(rotated.behind());
        assertEquals(RADIUS, rotated.x());
        assertEquals(0, rotated.y());
    }

    @Test void verticalDirectionsAndExtremeCameraPitchRemainFiniteAndBounded() {
        var down = RotationIndicatorGeometry.project(camera(0, 0), 0, 90, RADIUS);
        var up = RotationIndicatorGeometry.project(camera(0, 0), 0, -90, RADIUS);
        assertNotNull(down);
        assertNotNull(up);
        assertEquals(RADIUS, down.y(), 0.0001F);
        assertEquals(-RADIUS, up.y(), 0.0001F);
        assertFalse(down.behind());
        assertFalse(up.behind());
        assertNull(RotationIndicatorGeometry.project(camera(73, 90), 73, 90, RADIUS));
        var tilt = RotationIndicatorGeometry.project(camera(73, 90), 73, 70, RADIUS);
        assertNotNull(tilt);
        assertEquals(-RADIUS * 20 / 90, tilt.y(), 0.0001F);
        var wrapped = RotationIndicatorGeometry.project(camera(12, -17), Float.MAX_VALUE, Float.MAX_VALUE, RADIUS);
        assertNotNull(wrapped);
        assertTrue(Float.isFinite(wrapped.x()));
        assertTrue(Float.isFinite(wrapped.y()));
        assertTrue(Math.hypot(wrapped.x(), wrapped.y()) <= RADIUS + 0.0001);
    }

    @Test void lengthNeverExceedsTheConfiguredRadius() {
        for (int yaw = -180; yaw <= 180; yaw += 15) {
            for (int pitch = -90; pitch <= 90; pitch += 15) {
                var pointer = RotationIndicatorGeometry.project(camera(12, -17), yaw, pitch, 7);
                if (pointer != null) assertTrue(Math.hypot(pointer.x(), pointer.y()) <= 7.00001);
            }
        }
        var side = RotationIndicatorGeometry.project(camera(0, 0), 90, 0, 7);
        assertNotNull(side);
        assertEquals(7, side.x(), 0.0001F);
        assertFalse(side.behind());
    }

    @Test void translationDoesNotAffectTheDirection() {
        Matrix4f translated = camera(21, -8).translate(1_000_000, -2_000_000, 3_000_000);
        var expected = RotationIndicatorGeometry.project(camera(21, -8), 50, 12, RADIUS);
        assertEquals(expected, RotationIndicatorGeometry.project(translated, 50, 12, RADIUS));
    }

    @Test void invalidInputsCannotProduceAnInvalidPointer() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertNull(RotationIndicatorGeometry.project(camera(0, 0), invalid, 0, RADIUS));
            assertNull(RotationIndicatorGeometry.project(camera(0, 0), 0, invalid, RADIUS));
            assertNull(RotationIndicatorGeometry.project(camera(0, 0), 45, 0, invalid));
            assertNull(RotationIndicatorGeometry.project(new Matrix4f().m00(invalid), 45, 0, RADIUS));
        }
        assertNull(RotationIndicatorGeometry.project(camera(0, 0), 45, 0, 0));
        assertNull(RotationIndicatorGeometry.project(camera(0, 0), 45, 0, -1));
        assertNull(RotationIndicatorGeometry.project(new Matrix4f().zero(), 45, 0, RADIUS));
        assertNull(RotationIndicatorGeometry.project(null, 45, 0, RADIUS));
    }

    private static Matrix4f camera(float yaw, float pitch) {
        return new Matrix4f().rotationX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180));
    }
}
