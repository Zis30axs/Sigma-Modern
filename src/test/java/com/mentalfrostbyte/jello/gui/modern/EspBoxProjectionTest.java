package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class EspBoxProjectionTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final float NEAR = 0.05F;
    private static final float FAR = 100F;

    private static Matrix4f projection(boolean reverse) {
        return new Matrix4f().setPerspective((float) Math.toRadians(90), WIDTH / (float) HEIGHT,
                reverse ? FAR : NEAR, reverse ? NEAR : FAR, true);
    }

    @Test
    void projectsTheWholeBodyUsingTheNearestCorners() {
        AABB body = new AABB(-0.5, -1, -5, 0.5, 1, -4);
        assertBounds(project(body), 362.5F, 225F, 437.5F, 375F);
    }

    @Test
    void clipsNearPlaneCrossingsBeforeDivisionForBothDepthOrders() {
        // The eye is outside the narrow X interval, while half the box is behind the camera.
        AABB crossing = new AABB(0.01, -0.01, -0.2, 0.03, 0.01, 0.1);
        EspBoxProjection.Bounds reverse = project(crossing);
        EspBoxProjection.Bounds regular = EspBoxProjection.project(projection(false), crossing, Vec3.ZERO, WIDTH, HEIGHT);
        assertNotNull(reverse);
        assertBounds(reverse, 415F, 240F, 580F, 360F);
        assertBounds(regular, reverse.left(), reverse.top(), reverse.right(), reverse.bottom());
    }

    @Test
    void rejectsBehindTheEyeAndBeyondEitherPhysicalDepthPlane() {
        assertNull(project(new AABB(-0.5, -1, 1, 0.5, 1, 2)));
        assertNull(project(new AABB(0.01, -0.01, -0.04, 0.03, 0.01, -0.01)));
        assertNull(project(new AABB(-0.5, -1, -120, 0.5, 1, -101)));
        assertNotNull(project(new AABB(-0.5, -1, -101, 0.5, 1, -99)));
    }

    @Test
    void cropsToTheScreenButDoesNotInventOffscreenTargets() {
        assertBounds(project(new AABB(5, -1, -5, 8, 1, -4)), 700F, 225F, 800F, 375F);
        assertNull(project(new AABB(20, -1, -5, 21, 1, -4)));
        assertNull(project(new AABB(-21, -1, -5, -20, 1, -4)));
        assertNull(project(new AABB(-0.5, 20, -5, 0.5, 21, -4)));
    }

    @Test
    void aFaceCanCoverTheViewEvenWhenEveryOriginalCornerIsOutside() {
        // Point/edge-only culling loses this visible face: its corners are outside four different planes.
        assertBounds(project(new AABB(-100, -100, -11, 100, 100, -10)), 0F, 0F, WIDTH, HEIGHT);
    }

    @Test
    void hidesBoxesContainingOrTouchingTheCameraRatherThanDrawingAHugeFrame() {
        assertNull(project(new AABB(-1, -1, -1, 1, 1, 1)));
        assertNull(project(new AABB(-1, -1, -1, 1, 1, 0)));
        assertNull(project(new AABB(0, 0, 0, 1, 1, 1)));
    }

    @Test
    void cameraRotationAndFarWorldCoordinatesKeepTheSameBounds() {
        AABB body = new AABB(-0.5, -1, -5, 0.5, 1, -4);
        Vec3 distant = new Vec3(29_999_990.125, 255.75, -29_999_900.5);
        EspBoxProjection.Bounds local = project(body);
        assertNotNull(local);
        assertBounds(EspBoxProjection.project(projection(true), body.move(distant), distant, WIDTH, HEIGHT),
                local.left(), local.top(), local.right(), local.bottom());
        assertBounds(EspBoxProjection.project(projection(true).rotateY((float) Math.PI / 2),
                new AABB(4, -1, -0.5, 5, 1, 0.5), Vec3.ZERO, WIDTH, HEIGHT),
                local.left(), local.top(), local.right(), local.bottom());
    }

    @Test
    void malformedCoordinatesAndMatricesNeverProduceDrawBounds() {
        AABB body = new AABB(-0.5, -1, -5, 0.5, 1, -4);
        assertNull(EspBoxProjection.project(projection(true), body, Vec3.ZERO, 0, HEIGHT));
        assertNull(EspBoxProjection.project(projection(true), body, new Vec3(Double.NaN, 0, 0), WIDTH, HEIGHT));
        assertNull(EspBoxProjection.project(projection(true).m00(Float.NaN), body, Vec3.ZERO, WIDTH, HEIGHT));
        assertNull(EspBoxProjection.project(projection(true).m32(Float.POSITIVE_INFINITY), body, Vec3.ZERO, WIDTH, HEIGHT));
        assertNull(project(new AABB(Double.NEGATIVE_INFINITY, -1, -5, 1, 1, -4)));
        assertNull(project(new AABB(0, -1, -5, 0, 1, -4)));
    }

    private static EspBoxProjection.Bounds project(AABB box) {
        return EspBoxProjection.project(projection(true), box, Vec3.ZERO, WIDTH, HEIGHT);
    }

    private static void assertBounds(EspBoxProjection.Bounds actual, float left, float top, float right, float bottom) {
        assertNotNull(actual);
        assertEquals(left, actual.left(), 0.002F);
        assertEquals(top, actual.top(), 0.002F);
        assertEquals(right, actual.right(), 0.002F);
        assertEquals(bottom, actual.bottom(), 0.002F);
        assertTrue(actual.left() >= 0 && actual.top() >= 0 && actual.right() <= WIDTH && actual.bottom() <= HEIGHT);
    }
}
