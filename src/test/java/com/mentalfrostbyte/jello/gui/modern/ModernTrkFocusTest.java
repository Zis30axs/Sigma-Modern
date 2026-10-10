package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import com.mentalfrostbyte.jello.module.impl.gui.Radar;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModernTrkFocusTest {
    private static final int WIDTH = 800, HEIGHT = 600;
    private static final UUID TARGET = new UUID(3, 7), OTHER = new UUID(5, 9);
    private static final AABB BODY = new AABB(-0.3, -0.9, -4.3, 0.3, 0.9, -3.7);
    private static final ModernTrkFocus.Marker MARKER = new ModernTrkFocus.Marker(370, 230, 430, 370);

    @AfterEach
    void clearProjectionAndFocus() {
        ModernHudProjection.beginFrame(null, null);
        ModernTrkFocus.beginFrame(null, false);
    }

    @Test
    void twsSelectionDoesNotDimAnyoneUntilManualTrackAndReleaseRestoresIt() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        List<RadarContacts.Detection> detections = List.of(new RadarContacts.Detection(
                new RadarContacts.Contact(7, "Target", new Vec3(0, 0, -4), Vec3.ZERO, false, 0), 0, 0, TARGET));
        RadarContacts.Frame search = tracker.advance(detections, Radar.Mode.TWS, 0, 60, 120, 7);
        assertEquals(7, search.selectedId(), "TWS does select a target without obtaining a manual lock");
        assertNull(focus(Radar.Focus.FADE_OTHERS, search.tracking(), MARKER));

        tracker.toggleTrack(detections);
        RadarContacts.Frame locked = tracker.advance(detections, Radar.Mode.TWS, 0, 60, 120, 7);
        ModernTrkFocus.Frame focused = focus(Radar.Focus.FADE_OTHERS, locked.tracking(), MARKER);
        assertNotNull(focused);
        assertEquals(0.2F, focused.opacity(9, OTHER));
        assertEquals(1F, focused.opacity(7, TARGET));

        tracker.toggleTrack(detections);
        RadarContacts.Frame released = tracker.advance(detections, Radar.Mode.TWS, 0, 60, 120, 7);
        assertEquals(7, released.selectedId(), "restored TWS selection cannot accidentally prolong focus");
        assertNull(focus(Radar.Focus.FADE_OTHERS, released.tracking(), MARKER));
    }

    @Test
    void identityNeedsBothIdAndUuidAndOnlyTargetPolicyHidesOtherEntities() {
        ModernTrkFocus.Frame fade = focus(Radar.Focus.FADE_OTHERS, true, MARKER);
        assertNotNull(fade);
        assertTrue(fade.targets(7, TARGET));
        assertFalse(fade.targets(7, OTHER), "an ID reused by another entity must not inherit the lock");
        assertFalse(fade.targets(8, TARGET), "the same profile reappearing under another entity ID is a new target");
        assertEquals(0.2F, fade.opacity(7, OTHER));
        assertEquals(0.2F, fade.opacity(8, TARGET));

        ModernTrkFocus.Frame only = focus(Radar.Focus.TARGET_ONLY, true, MARKER);
        assertNotNull(only);
        assertEquals(1F, only.opacity(7, TARGET));
        assertEquals(0F, only.opacity(9, OTHER));
        assertEquals(0F, only.opacity(7, OTHER));
    }

    @Test
    void disabledFocusOrAnUndrawableLockCannotSuppressRegularMarkers() {
        assertNull(focus(Radar.Focus.OFF, true, MARKER));
        assertNull(focus(Radar.Focus.FADE_OTHERS, true, null));
        assertNull(ModernTrkFocus.snapshot(Radar.Focus.FADE_OTHERS, true, 7, null, MARKER,
                Radar.Style.WT, 4, 0.2F, 0));
        assertNull(ModernTrkFocus.snapshot(Radar.Focus.FADE_OTHERS, true, 7, TARGET, MARKER,
                Radar.Style.WT, Double.NaN, 0.2F, 0));
        ModernTrkFocus.beginFrame(null, false);
        assertEquals(1F, ModernTrkFocus.opacity(7, TARGET));
        assertEquals(1F, ModernTrkFocus.opacity(9, OTHER));
        assertFalse(ModernTrkFocus.replacesMarker(7, TARGET));
    }

    @Test
    void visibleBodyMovesTheFrameAndAnOffscreenBodyCannotLeaveAnEdgeMarker() {
        begin(Vec3.ZERO, true);
        ModernTrkFocus.Marker initial = project(BODY);
        ModernTrkFocus.Marker moved = project(BODY.move(1, 0.5, 0));
        assertNotNull(initial);
        assertNotNull(moved);
        assertEquals(400F, centerX(initial), 0.002F);
        assertEquals(300F, centerY(initial), 0.002F);
        assertEquals(475F, centerX(moved), 0.002F);
        assertEquals(262.5F, centerY(moved), 0.002F);
        AABB sliver = new AABB(5.5, -1, -5, 8, 1, -4);
        assertNotNull(ModernHudProjection.projectBox(sliver, WIDTH, HEIGHT));
        assertNull(project(sliver));
        assertNull(focus(Radar.Focus.FADE_OTHERS, true, project(sliver)));
        assertNotNull(focus(Radar.Focus.FADE_OTHERS, true, project(BODY)), "re-entry recovers from current geometry");
    }

    @Test
    void closeLargeAndNearPlaneTargetsStayBoundedWithoutHidingTheScene() {
        begin(Vec3.ZERO, true);
        for (AABB body : List.of(new AABB(-100, -100, -11, 100, 100, -10),
                new AABB(-0.3, -0.9, -0.15, 0.3, 0.9, -0.1))) {
            ModernTrkFocus.Marker marker = project(body);
            assertNotNull(marker);
            assertTrue(marker.width() <= 144F && marker.height() <= 180F);
            assertMarkerInsideViewport(marker);
        }
        AABB crossing = new AABB(0.001, -0.005, -0.15, 0.011, 0.005, 0.01);
        ModernTrkFocus.Marker reverseDepth = project(crossing);
        assertNotNull(reverseDepth);
        assertMarkerInsideViewport(reverseDepth);
        begin(Vec3.ZERO, false);
        assertMarkerEquals(reverseDepth, project(crossing));
        assertNull(project(crossing.move(0, 0, 0.03)), "an anchor inside the near plane has no substitute");
        assertNull(project(new AABB(-1, -1, -1, 1, 1, 1)), "the camera cannot be inside its own tracking box");
        assertNull(project(new AABB(-0.3, -0.9, 1, 0.3, 0.9, 2)), "a target behind the view has no marker");
        assertNull(project(BODY.move(0, 20, 0)), "a target straight above cannot be pinned to the top edge");
        assertNull(ModernTrkFocus.project(BODY, 30, 30), "too-small viewports must not manufacture an oversized marker");
    }

    @Test
    void labelsStayInsideEveryViewportEdgeEvenWhenTheMarkerReachesIt() {
        ModernTrkFocus.Label caption = ModernTrkFocus.label(MARKER, 48, WIDTH, HEIGHT);
        assertEquals(MARKER.bottom() + 5F, caption.y(), "the target NameTag keeps its space above the body");
        for (ModernTrkFocus.Marker marker : List.of(new ModernTrkFocus.Marker(4, 4, 32, 42),
                new ModernTrkFocus.Marker(768, 4, 796, 42), new ModernTrkFocus.Marker(4, 558, 32, 596),
                new ModernTrkFocus.Marker(768, 558, 796, 596), new ModernTrkFocus.Marker(4, 4, 796, 596))) {
            for (float textWidth : new float[] {48F, 1400F}) {
                ModernTrkFocus.Label label = ModernTrkFocus.label(marker, textWidth, WIDTH, HEIGHT);
                assertTrue(label.x() >= 4 && label.y() >= 4);
                assertTrue(label.x() + label.width() <= WIDTH - 4);
                assertTrue(label.y() + label.height() <= HEIGHT - 4);
            }
        }
    }

    @Test
    void cameraTranslationAtWorldBorderAndUnsupportedViewsDoNotReuseOldGeometry() {
        begin(Vec3.ZERO, true);
        ModernTrkFocus.Marker local = project(BODY);
        assertNotNull(local);
        Vec3 farAway = new Vec3(29_999_990.125, 255.75, -29_999_900.5);
        begin(farAway, true);
        assertMarkerEquals(local, project(BODY.move(farAway)));
        ModernHudProjection.beginFrame(null, null);
        assertNull(project(BODY.move(farAway)));
        assertNull(focus(Radar.Focus.TARGET_ONLY, true, project(BODY.move(farAway))));
    }

    private static ModernTrkFocus.Frame focus(Radar.Focus policy, boolean tracking, ModernTrkFocus.Marker marker) {
        return ModernTrkFocus.snapshot(policy, tracking, 7, TARGET, marker, Radar.Style.WT, 4, 0.2F, 0);
    }

    private static ModernTrkFocus.Marker project(AABB box) {
        return ModernTrkFocus.project(box, WIDTH, HEIGHT);
    }

    private static float centerX(ModernTrkFocus.Marker marker) { return (marker.left() + marker.right()) / 2F; }
    private static float centerY(ModernTrkFocus.Marker marker) { return (marker.top() + marker.bottom()) / 2F; }

    private static void begin(Vec3 position, boolean reverse) {
        CameraRenderState camera = new CameraRenderState();
        camera.initialized = true;
        camera.pos = position;
        ModernHudProjection.beginFrame(camera, new Matrix4f().setPerspective((float) Math.PI / 2,
                WIDTH / (float) HEIGHT, reverse ? 100F : 0.05F, reverse ? 0.05F : 100F, true));
    }

    private static void assertMarkerInsideViewport(ModernTrkFocus.Marker marker) {
        assertTrue(Float.isFinite(marker.left()) && Float.isFinite(marker.top())
                && Float.isFinite(marker.right()) && Float.isFinite(marker.bottom()));
        assertTrue(marker.left() >= 4 && marker.top() >= 4);
        assertTrue(marker.right() <= WIDTH - 4 && marker.bottom() <= HEIGHT - 4);
        assertTrue(marker.width() > 0 && marker.height() > 0);
    }

    private static void assertMarkerEquals(ModernTrkFocus.Marker expected, ModernTrkFocus.Marker actual) {
        assertNotNull(actual);
        assertEquals(expected.left(), actual.left(), 0.002F);
        assertEquals(expected.top(), actual.top(), 0.002F);
        assertEquals(expected.right(), actual.right(), 0.002F);
        assertEquals(expected.bottom(), actual.bottom(), 0.002F);
    }
}
