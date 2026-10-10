package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModernHudSegmentProjectionTest {
    @AfterEach void reset() { ModernHudProjection.beginFrame(null, null); }

    @Test void visibleSegmentsUseTheCurrentCameraWithWorldBorderPrecision() {
        Vec3 camera = new Vec3(30_000_000, 64, 30_000_000);
        begin(camera, perspective(true));
        var segment = ModernHudProjection.projectSegment(camera.add(-1, 0, -4), camera.add(1, 0, -4), 800, 600);
        assertNotNull(segment);
        assertEquals(300F, segment.x1(), 0.01F);
        assertEquals(500F, segment.x2(), 0.01F);
        assertEquals(300F, segment.y1(), 0.01F);
        assertEquals(300F, segment.y2(), 0.01F);
    }

    @Test void twoOffscreenEndpointsStillDrawThePartCrossingTheViewport() {
        begin(Vec3.ZERO, perspective(true));
        var segment = ModernHudProjection.projectSegment(new Vec3(-4, 0, -2), new Vec3(4, 0, -2), 800, 600);
        assertNotNull(segment);
        assertEquals(0F, segment.x1(), 0.01F);
        assertEquals(800F, segment.x2(), 0.01F);
        assertEquals(300F, segment.y1(), 0.01F);
        assertEquals(300F, segment.y2(), 0.01F);
        assertNull(ModernHudProjection.projectSegment(new Vec3(-5, 0, -2), new Vec3(-4, 0, -2), 800, 600));
    }

    @Test void clippingNearPlaneWorksInBothDepthDirections() {
        for (boolean reverse : new boolean[] {false, true}) {
            begin(Vec3.ZERO, perspective(reverse));
            var segment = ModernHudProjection.projectSegment(new Vec3(0, 0, 1), new Vec3(0.1, 0.1, -2), 800, 600);
            assertBounded(segment);
            assertEquals(420F, segment.x2(), 0.01F);
            assertEquals(285F, segment.y2(), 0.01F);
            assertNull(ModernHudProjection.projectSegment(new Vec3(0, 0, 1), new Vec3(0.1, 0.1, 2), 800, 600));
        }
    }

    @Test void eyeOriginAndNearPlaneDoNotProduceAnUnboundedStroke() {
        begin(Vec3.ZERO, perspective(true));
        var segment = ModernHudProjection.projectSegment(Vec3.ZERO, new Vec3(0.5, -0.5, -2), 800, 600);
        assertBounded(segment);
        assertEquals(500F, segment.x1(), 0.01F);
        assertEquals(375F, segment.y1(), 0.01F);
        assertEquals(segment.x1(), segment.x2(), 0.01F);
        assertEquals(segment.y1(), segment.y2(), 0.01F);
    }

    @Test void rejectsBeyondDepthRangeAndNonFiniteInputs() {
        begin(Vec3.ZERO, perspective(true));
        assertNull(ModernHudProjection.projectSegment(new Vec3(0, 0, -200), new Vec3(1, 0, -300), 800, 600));
        assertNull(ModernHudProjection.projectSegment(new Vec3(Double.NaN, 0, -2), new Vec3(1, 0, -3), 800, 600));
        assertNull(ModernHudProjection.projectSegment(new Vec3(0, 0, -2), new Vec3(Double.POSITIVE_INFINITY, 0, -3), 800, 600));
        assertNull(ModernHudProjection.projectSegment(new Vec3(0, 0, -2), new Vec3(1, 0, -3), 0, 600));
    }

    @Test void unavailableFrameCannotReuseItsOldWorldProjection() {
        begin(Vec3.ZERO, perspective(true));
        assertNotNull(ModernHudProjection.projectSegment(new Vec3(0, 0, -2), new Vec3(1, 0, -3), 800, 600));
        ModernHudProjection.beginFrame(null, null);
        assertNull(ModernHudProjection.projectSegment(new Vec3(0, 0, -2), new Vec3(1, 0, -3), 800, 600));
    }

    private static Matrix4f perspective(boolean reverse) {
        return new Matrix4f().setPerspective((float) Math.PI / 2F, 1F, reverse ? 100F : 0.05F,
                reverse ? 0.05F : 100F, true);
    }

    private static void begin(Vec3 position, Matrix4f projection) {
        CameraRenderState camera = new CameraRenderState();
        camera.initialized = true;
        camera.pos = position;
        ModernHudProjection.beginFrame(camera, projection);
    }

    private static void assertBounded(ModernHudProjection.Segment segment) {
        assertNotNull(segment);
        for (float x : new float[] {segment.x1(), segment.x2()}) assertTrue(Float.isFinite(x) && x >= 0F && x <= 800F);
        for (float y : new float[] {segment.y1(), segment.y2()}) assertTrue(Float.isFinite(y) && y >= 0F && y <= 600F);
    }
}
