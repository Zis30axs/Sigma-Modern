package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class NameTagProjectionTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final float NEAR = 0.05F;
    private static final float FAR = 100.0F;

    private static Matrix4f reverseProjection() {
        // Projection.getMatrix swaps the physical near/far planes for Minecraft's reverse-Z buffer.
        return new Matrix4f().setPerspective((float) Math.toRadians(90), WIDTH / (float) HEIGHT, FAR, NEAR, true);
    }

    @Test
    void centerAndScreenAxesFollowTheCurrentCamera() {
        Matrix4f projection = reverseProjection();
        assertPoint(NameTagProjection.project(projection, 0, 0, -10, WIDTH, HEIGHT), 400, 300);
        assertPoint(NameTagProjection.project(projection, 1, 1, -10, WIDTH, HEIGHT), 430, 270);

        // A camera rotated toward +X sees a +X target in the center immediately, without a frame cache.
        Matrix4f turned = new Matrix4f(projection).rotateY((float) Math.PI / 2);
        assertPoint(NameTagProjection.project(turned, 10, 0, 0, WIDTH, HEIGHT), 400, 300);

        // Camera-relative subtraction remains precise even at distant world coordinates.
        double cameraX = 29_999_990.0;
        double targetX = cameraX + 1.0;
        assertPoint(NameTagProjection.project(projection, targetX - cameraX, 0, -10, WIDTH, HEIGHT), 430, 300);
        assertPoint(NameTagProjection.project(projection, targetX - (cameraX + 1), 0, -10, WIDTH, HEIGHT), 400, 300);
    }

    @Test
    void rejectsBehindTheCameraAndBothDepthClipPlanes() {
        Matrix4f projection = reverseProjection();
        assertNull(NameTagProjection.project(projection, 0, 0, 10, WIDTH, HEIGHT));
        assertNull(NameTagProjection.project(projection, 0, 0, 0, WIDTH, HEIGHT));
        assertNull(NameTagProjection.project(projection, 0, 0, -NEAR / 2, WIDTH, HEIGHT));
        assertNotNull(NameTagProjection.project(projection, 0, 0, -NEAR * 2, WIDTH, HEIGHT));
        assertNotNull(NameTagProjection.project(projection, 0, 0, -FAR * 0.99, WIDTH, HEIGHT));
        assertNull(NameTagProjection.project(projection, 0, 0, -FAR * 1.01, WIDTH, HEIGHT));
    }

    @Test
    void supportsTheShaderPacksNegativeOneToOneDepthRange() {
        Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(90),
                WIDTH / (float) HEIGHT, NEAR, FAR, false);
        // This point lies between the near plane and the zero-depth plane, so a [0, 1] test rejects it.
        assertNull(NameTagProjection.project(projection, 0, 0, -NEAR * 1.25, WIDTH, HEIGHT));
        assertNotNull(NameTagProjection.project(projection, 0, 0, -NEAR * 1.25, WIDTH, HEIGHT, false));
        assertNull(NameTagProjection.project(projection, 0, 0, -NEAR / 2, WIDTH, HEIGHT, false));
        assertNull(NameTagProjection.project(projection, 0, 0, -FAR * 1.01, WIDTH, HEIGHT, false));
    }

    @Test
    void keepsOffscreenAnchorsForTheCallersCardIntersectionCheck() {
        NameTagProjection.Point point = NameTagProjection.project(reverseProjection(), 100, -100, -10, WIDTH, HEIGHT);
        assertNotNull(point);
        assertTrue(point.x() > WIDTH);
        assertTrue(point.y() > HEIGHT);
    }

    @Test
    void rejectsInvalidCoordinatesMatricesAndViewports() {
        Matrix4f projection = reverseProjection();
        assertNull(NameTagProjection.project(projection, Double.NaN, 0, -10, WIDTH, HEIGHT));
        assertNull(NameTagProjection.project(projection, 0, Double.POSITIVE_INFINITY, -10, WIDTH, HEIGHT));
        assertNull(NameTagProjection.project(projection, 0, 0, Double.NEGATIVE_INFINITY, WIDTH, HEIGHT));
        assertNull(NameTagProjection.project(new Matrix4f(projection).m00(Float.NaN), 0, 0, -10, WIDTH, HEIGHT));
        assertNull(NameTagProjection.project(new Matrix4f(projection).m32(Float.POSITIVE_INFINITY), 0, 0, -10, WIDTH, HEIGHT));
        assertNull(NameTagProjection.project(projection, 0, 0, -10, 0, HEIGHT));
        assertNull(NameTagProjection.project(projection, 0, 0, -10, WIDTH, -1));
        assertNull(NameTagProjection.project(projection, Double.MAX_VALUE, 0, -10, WIDTH, HEIGHT));
    }

    private static void assertPoint(NameTagProjection.Point actual, float expectedX, float expectedY) {
        assertNotNull(actual);
        assertEquals(expectedX, actual.x(), 0.001F);
        assertEquals(expectedY, actual.y(), 0.001F);
    }
}
