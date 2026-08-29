package com.mentalfrostbyte.jello.event.impl.game.render;

import com.mentalfrostbyte.jello.event.Event;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;

/**
 * Fired after the level has been rendered and before the held item and screen effects, which is
 * where world-space overlays belong.
 *
 * <p>Carries the camera state and the matrices actually used for the frame. The model-view matrix is
 * not the raw camera rotation: view bobbing and the portal/nausea spin are folded into it, so
 * anything drawn from a different matrix will not line up with the world.</p>
 */
public class EventRender3D extends Event {

    private final CameraRenderState camera;
    private final Matrix4fc modelViewMatrix;
    private final Matrix4fc projectionMatrix;
    private final float partialTick;

    public EventRender3D(final CameraRenderState camera,
                         final Matrix4fc modelViewMatrix,
                         final Matrix4fc projectionMatrix,
                         final float partialTick) {
        this.camera = camera;
        this.modelViewMatrix = modelViewMatrix;
        this.projectionMatrix = projectionMatrix;
        this.partialTick = partialTick;
    }

    public CameraRenderState getCamera() {
        return this.camera;
    }

    public Matrix4fc getModelViewMatrix() {
        return this.modelViewMatrix;
    }

    public Matrix4fc getProjectionMatrix() {
        return this.projectionMatrix;
    }

    public float getPartialTick() {
        return this.partialTick;
    }
}
