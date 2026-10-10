package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModernAuraIndicatorTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final AABB BODY = new AABB(-0.3, -0.9, -4.3, 0.3, 0.9, -3.7);

    @AfterEach
    void clearProjection() {
        ModernHudProjection.beginFrame(null, null);
    }

    @Test
    void aNewTargetPositionReplacesTheOldAnchorWithoutRetainingAnOffscreenTarget() {
        begin(Vec3.ZERO, true);
        ModernAuraIndicator.Marker initial = project(BODY);
        assertNotNull(initial);
        assertEquals(400F, initial.x(), 0.002F);
        assertEquals(300F, initial.y(), 0.002F);

        ModernAuraIndicator.Marker moved = project(BODY.move(1, 0.5, 0));
        assertNotNull(moved);
        assertEquals(475F, moved.x(), 0.002F);
        assertEquals(262.5F, moved.y(), 0.002F);
        assertNull(project(BODY.move(20, 0, 0)), "losing a target cannot reuse its last visible marker");
        assertEquals(initial, project(BODY), "reacquiring a target uses its actual current anchor");
    }

    @Test
    void aVisibleSliverDoesNotDragAnOffscreenBodyAnchorOntoTheScreenEdge() {
        begin(Vec3.ZERO, true);
        AABB partlyVisible = new AABB(5.5, -1, -5, 8, 1, -4);
        assertNotNull(ModernHudProjection.projectBox(partlyVisible, WIDTH, HEIGHT),
                "part of the body remains visible, so box rejection alone is insufficient");
        assertNull(project(partlyVisible), "the actual body center is outside the viewport");
        assertNull(project(new AABB(-8, -1, -5, -5.5, 1, -4)));
        assertNull(project(new AABB(-0.5, 4, -5, 0.5, 6, -4)));
    }

    @Test
    void movingTheCameraAndTargetTogetherAtTheWorldBorderDoesNotShiftTheMarker() {
        begin(Vec3.ZERO, true);
        ModernAuraIndicator.Marker local = project(BODY.move(0.75, 0.25, 0));
        assertNotNull(local);

        Vec3 farAway = new Vec3(29_999_990.125, 255.75, -29_999_900.5);
        begin(farAway, true);
        ModernAuraIndicator.Marker distant = project(BODY.move(0.75, 0.25, 0).move(farAway));
        assertMarkerEquals(local, distant);
    }

    @Test
    void cameraRotationPlacesTheMarkerOnTheEntityInsteadOfTheUnrotatedWorldAxis() {
        begin(Vec3.ZERO, true);
        ModernAuraIndicator.Marker ahead = project(BODY);
        assertNotNull(ahead);

        ModernHudProjection.beginFrame(camera(Vec3.ZERO), projection(true).rotateY((float) Math.PI / 2));
        assertMarkerEquals(ahead, project(new AABB(3.7, -0.9, -0.3, 4.3, 0.9, 0.3)));
    }

    @Test
    void clippedNearPlaneCrossingsRemainFiniteAndStableForBothDepthOrders() {
        // The box straddles the eye plane without containing the eye; its center remains beyond the near plane.
        AABB crossing = new AABB(0.001, -0.005, -0.15, 0.011, 0.005, 0.01);
        begin(Vec3.ZERO, true);
        ModernAuraIndicator.Marker reverse = project(crossing);
        assertNotNull(reverse);
        assertTrue(Float.isFinite(reverse.x()) && Float.isFinite(reverse.y()) && Float.isFinite(reverse.radius()));

        ModernAuraIndicator.Marker closer = project(crossing.move(0, 0, 0.001));
        assertNotNull(closer);
        assertTrue(Math.abs(closer.x() - reverse.x()) < 2F,
                "a tiny depth change near clipping must not make the marker jump across the screen");
        assertEquals(reverse.y(), closer.y(), 0.002F);

        begin(Vec3.ZERO, false);
        assertMarkerEquals(reverse, project(crossing));
        assertNull(project(crossing.move(0, 0, 0.03)),
                "a clipped body cannot substitute a false anchor when its center is inside the near plane");
    }

    @Test
    void veryLargeOrCloseTargetsStayCompactAndBoxesContainingTheEyeHaveNoMarker() {
        begin(Vec3.ZERO, true);
        ModernAuraIndicator.Marker large = project(new AABB(-100, -100, -11, 100, 100, -10));
        ModernAuraIndicator.Marker close = project(new AABB(-0.3, -0.9, -0.15, 0.3, 0.9, -0.1));
        assertNotNull(large);
        assertNotNull(close);
        assertEquals(400F, close.x(), 0.002F);
        assertEquals(300F, close.y(), 0.002F);
        assertTrue(large.radius() > 0F && large.radius() <= 30F, "even a huge body keeps a compact lock-on marker");
        assertTrue(close.radius() > 0F && close.radius() <= 30F, "nearby targets cannot cover the viewport");
        assertNull(project(new AABB(-1, -1, -1, 1, 1, 1)));
        assertNull(project(new AABB(-1, -1, -1, 1, 1, 0)), "touching the eye is also rejected");
        assertNull(project(new AABB(-0.3, -0.9, 1, 0.3, 0.9, 2)), "behind-camera targets have no marker");
    }

    @Test
    void unsupportedOrMissingCameraFramesClearThePreviousMarker() {
        begin(Vec3.ZERO, true);
        assertNotNull(project(BODY));
        ModernHudProjection.beginFrame(camera(Vec3.ZERO), null);
        assertNull(project(BODY));

        begin(Vec3.ZERO, true);
        ModernHudProjection.beginFrame(null, projection(true));
        assertNull(project(BODY));

        CameraRenderState special = camera(Vec3.ZERO);
        special.isPanoramicMode = true;
        ModernHudProjection.beginFrame(special, projection(true));
        assertNull(project(BODY));

        special.isPanoramicMode = false;
        special.isFrustumCaptured = true;
        ModernHudProjection.beginFrame(special, projection(true));
        assertNull(project(BODY));

        special.isFrustumCaptured = false;
        special.initialized = false;
        ModernHudProjection.beginFrame(special, projection(true));
        assertNull(project(BODY));

        begin(Vec3.ZERO, true);
        assertNotNull(project(BODY), "an ordinary frame can recover after an unsupported view");
    }

    private static void begin(Vec3 position, boolean reverse) {
        ModernHudProjection.beginFrame(camera(position), projection(reverse));
    }

    private static CameraRenderState camera(Vec3 position) {
        CameraRenderState camera = new CameraRenderState();
        camera.initialized = true;
        camera.pos = position;
        return camera;
    }

    private static Matrix4f projection(boolean reverse) {
        return new Matrix4f().setPerspective((float) Math.PI / 2, WIDTH / (float) HEIGHT,
                reverse ? 100F : 0.05F, reverse ? 0.05F : 100F, true);
    }

    private static ModernAuraIndicator.Marker project(AABB box) {
        return ModernAuraIndicator.project(box, WIDTH, HEIGHT);
    }

    private static void assertMarkerEquals(ModernAuraIndicator.Marker expected, ModernAuraIndicator.Marker actual) {
        assertNotNull(actual);
        assertEquals(expected.x(), actual.x(), 0.002F);
        assertEquals(expected.y(), actual.y(), 0.002F);
        assertEquals(expected.radius(), actual.radius(), 0.002F);
    }
}
