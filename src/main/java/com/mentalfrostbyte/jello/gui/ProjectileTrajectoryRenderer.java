package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.gui.modern.ModernHudProjection;
import com.mentalfrostbyte.jello.gui.modern.NameTagProjection;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.render.Trajectories;
import com.mentalfrostbyte.jello.util.game.ProjectileTrajectories;
import com.mentalfrostbyte.jello.util.math.ReportedRotations;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Screen-space strokes over a world-space held-projectile prediction, shared by every client skin. */
public final class ProjectileTrajectoryRenderer {
    private static final int SHADOW = 0xC8071018;

    private ProjectileTrajectoryRenderer() {}

    public static void render(GuiGraphicsExtractor g, float partialTicks) {
        Trajectories options = Modules.enabled(Trajectories.class);
        if (options == null || !ModernHudProjection.available()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.level() != mc.level || !player.isAlive()
                || player.isSpectator() || player.isSleeping() || mc.getCameraEntity() != player
                || mc.gui.hud.isHidden() || mc.gui.screen() != null || mc.gui.overlay() != null) return;

        float yaw = player.getYRot(), pitch = player.getXRot();
        if (options.usesReportedAim()) {
            ReportedRotations.Sample look = player.getReportedRotations().sample(partialTicks,
                    yaw, pitch, player.yBodyRot);
            yaw = look.yaw();
            pitch = look.pitch();
        }
        List<ProjectileTrajectories.Path> paths = ProjectileTrajectories.predict(player, partialTicks,
                yaw, pitch, options.getRange());
        if (paths.isEmpty()) return;
        g.nextStratum();
        g.enableScissor(0, 0, g.guiWidth(), g.guiHeight());
        try {
            for (ProjectileTrajectories.Path path : paths) {
                int color = path.entityHit() ? options.getHitColor() : options.getColor();
                int shadow = SHADOW;
                if (path.preview()) {
                    color = fade(color, 0.55F);
                    shadow = fade(shadow, 0.55F);
                }
                List<Vec3> points = path.points();
                for (int index = 1; index < points.size(); index++) {
                    segment(g, points.get(index - 1), points.get(index), options.getThickness(), color, shadow);
                }
                if (options.showsHitMarker() && path.hitPosition() != null) {
                    hitMarker(g, path, options.getThickness(), color, shadow);
                }
            }
        } finally {
            g.disableScissor();
            g.nextStratum();
        }
    }

    private static void hitMarker(GuiGraphicsExtractor g, ProjectileTrajectories.Path path,
                                  float thickness, int color, int shadow) {
        Vec3 hit = path.hitPosition();
        if (hit == null) return;
        if (!path.entityHit() && path.hitFace() != null) {
            Direction.Axis axis = path.hitFace().getAxis();
            Vec3 u = axis == Direction.Axis.X ? new Vec3(0, 0.18, 0) : new Vec3(0.18, 0, 0);
            Vec3 v = axis == Direction.Axis.Z ? new Vec3(0, 0.18, 0) : new Vec3(0, 0, 0.18);
            Vec3 a = hit.subtract(u).subtract(v), b = hit.add(u).subtract(v);
            Vec3 c = hit.add(u).add(v), d = hit.subtract(u).add(v);
            segment(g, a, b, thickness, color, shadow);
            segment(g, b, c, thickness, color, shadow);
            segment(g, c, d, thickness, color, shadow);
            segment(g, d, a, thickness, color, shadow);
        }
        NameTagProjection.Point center = ModernHudProjection.project(hit, g.guiWidth(), g.guiHeight());
        if (center == null || center.x() < 0 || center.y() < 0
                || center.x() > g.guiWidth() || center.y() > g.guiHeight()) return;
        // Fixed-size center ink remains readable even when the hit face is distant or edge-on.
        if (path.entityHit()) {
            final int sides = 16;
            for (int index = 0; index < sides; index++) {
                double from = index * Math.TAU / sides, to = (index + 1) * Math.TAU / sides;
                stroke(g, center.x() + (float) Math.cos(from) * 5F, center.y() + (float) Math.sin(from) * 5F,
                        center.x() + (float) Math.cos(to) * 5F, center.y() + (float) Math.sin(to) * 5F,
                        thickness, color, shadow);
            }
        } else {
            stroke(g, center.x() - 2F, center.y(), center.x() + 2F, center.y(), thickness, color, shadow);
            stroke(g, center.x(), center.y() - 2F, center.x(), center.y() + 2F, thickness, color, shadow);
        }
    }

    private static void segment(GuiGraphicsExtractor g, Vec3 from, Vec3 to, float thickness, int color, int shadow) {
        ModernHudProjection.Segment projected = ModernHudProjection.projectSegment(from, to, g.guiWidth(), g.guiHeight());
        if (projected != null) stroke(g, projected.x1(), projected.y1(), projected.x2(), projected.y2(), thickness, color, shadow);
    }

    private static void stroke(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2,
                               float thickness, int color, int shadow) {
        line(g, x1, y1, x2, y2, thickness + 1.5F, shadow);
        line(g, x1, y1, x2, y2, thickness, color);
    }

    private static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float thickness, int color) {
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

    private static int fade(int color, float alpha) {
        return Math.round((color >>> 24) * alpha) << 24 | color & 0x00FFFFFF;
    }
}
