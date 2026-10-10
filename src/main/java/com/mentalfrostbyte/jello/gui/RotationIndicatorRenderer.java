package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.render.RotationIndicator;
import com.mentalfrostbyte.jello.util.math.ReportedRotations;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** A camera-relative direction cue, independent of the client skin and world marker projection. */
public final class RotationIndicatorRenderer {
    private static final float GAP = 4F;
    private static final int SHADOW = 0xD9071018;

    private RotationIndicatorRenderer() {}

    public static void render(GuiGraphicsExtractor g, float partialTicks) {
        RotationIndicator options = Modules.enabled(RotationIndicator.class);
        if (options == null) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.level() != mc.level || !player.isAlive()
                || player.isSpectator() || player.isSleeping() || mc.getCameraEntity() != player
                || !mc.options.getCameraType().isFirstPerson() || mc.gui.hud.isHidden()
                || mc.gui.screen() != null || mc.gui.overlay() != null) return;

        // Camera includes render-only snap rotations, whereas the player's own yaw may still follow the mouse.
        Camera camera = mc.gameRenderer.mainCamera();
        Matrix4f view = camera.getViewRotationMatrix(new Matrix4f());
        ReportedRotations.Sample look = player.getReportedRotations().sample(partialTicks,
                player.getYRot(), player.getXRot(), player.yBodyRot);
        float radius = Math.min(options.getLength(), Math.min(g.guiWidth(), g.guiHeight()) * 0.5F - 12F);
        if (radius <= GAP + 2F) return;
        g.nextStratum();
        if (options.showsBody()) draw(g, RotationIndicatorGeometry.project(view, look.bodyYaw(), 0F, radius),
                options.getBodyColor(), options.getThickness());
        if (options.showsHead()) draw(g, RotationIndicatorGeometry.project(view, look.yaw(), look.pitch(), radius),
                options.getHeadColor(), options.getThickness());
        g.nextStratum();
    }

    private static void draw(GuiGraphicsExtractor g, RotationIndicatorGeometry.@Nullable Pointer pointer,
                             int color, float thickness) {
        if (pointer == null) return;
        float projectedLength = (float) Math.hypot(pointer.x(), pointer.y());
        if (projectedLength < 0.01F) return;
        float dx = pointer.x() / projectedLength, dy = pointer.y() / projectedLength;
        // A small but real turn still gets a readable cue outside the crosshair itself.
        float length = Math.max(GAP + 4F, projectedLength);
        float x = g.guiWidth() / 2F, y = g.guiHeight() / 2F;
        if (pointer.behind()) {
            for (float along = GAP; along < length; along += 7F) {
                float end = Math.min(length, along + 4F);
                stroke(g, x + dx * along, y + dy * along, x + dx * end, y + dy * end, thickness, color);
            }
        } else {
            stroke(g, x + dx * GAP, y + dy * GAP, x + dx * length, y + dy * length, thickness, color);
        }
        float arrow = Math.min(5F, (length - GAP) * 0.5F);
        float tipX = x + dx * length, tipY = y + dy * length;
        stroke(g, tipX, tipY, tipX - dx * arrow - dy * arrow * 0.6F,
                tipY - dy * arrow + dx * arrow * 0.6F, thickness, color);
        stroke(g, tipX, tipY, tipX - dx * arrow + dy * arrow * 0.6F,
                tipY - dy * arrow - dx * arrow * 0.6F, thickness, color);
    }

    private static void stroke(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2,
                               float thickness, int color) {
        line(g, x1, y1, x2, y2, thickness + 1.5F, SHADOW);
        line(g, x1, y1, x2, y2, thickness, color);
    }

    private static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2,
                             float thickness, int color) {
        float length = (float) Math.hypot(x2 - x1, y2 - y1);
        if (length < 0.01F) return;
        g.pose().pushMatrix();
        try {
            g.pose().translate(x1, y1);
            g.pose().rotate((float) Math.atan2(y2 - y1, x2 - x1));
            g.pose().translate(0, -thickness * 0.5F);
            g.pose().scale(length, thickness);
            g.fill(0, 0, 1, 1, color);
        } finally {
            g.pose().popMatrix();
        }
    }
}
