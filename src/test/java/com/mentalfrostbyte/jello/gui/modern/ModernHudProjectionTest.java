package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModernHudProjectionTest {
    @AfterEach void reset() { ModernHudProjection.beginFrame(null, null); }

    @Test void snapshotsTheCurrentCameraAndClearsUnavailableFrames() {
        CameraRenderState camera = new CameraRenderState();
        camera.initialized = true;
        camera.pos = new Vec3(30_000_000, 60, 0);
        Matrix4f projection = new Matrix4f().setPerspective((float)Math.PI / 2, 1, 100, .05F, true);
        ModernHudProjection.beginFrame(camera, projection);
        projection.zero();
        camera.pos = Vec3.ZERO;
        var point = ModernHudProjection.project(new Vec3(30_000_000, 60, -10), 800, 600);
        assertNotNull(point);
        assertEquals(400F, point.x(), .001F);
        assertEquals(300F, point.y(), .001F);
        ModernHudProjection.beginFrame(null, null);
        assertFalse(ModernHudProjection.available());
        assertNull(ModernHudProjection.project(new Vec3(30_000_000, 60, -10), 800, 600));
    }

    @Test void specialCameraViewsCannotReuseARegularProjection() {
        CameraRenderState camera = new CameraRenderState();
        camera.initialized = true;
        camera.isPanoramicMode = true;
        ModernHudProjection.beginFrame(camera, new Matrix4f());
        assertFalse(ModernHudProjection.available());
        camera.isPanoramicMode = false;
        camera.isFrustumCaptured = true;
        ModernHudProjection.beginFrame(camera, new Matrix4f());
        assertFalse(ModernHudProjection.available());
    }
}
