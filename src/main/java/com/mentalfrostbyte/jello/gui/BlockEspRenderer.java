package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.gui.modern.EspBoxProjection;
import com.mentalfrostbyte.jello.gui.modern.ModernHudProjection;
import com.mentalfrostbyte.jello.gui.modern.NameTagProjection;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.render.BlockEsp;
import com.mentalfrostbyte.jello.module.impl.render.NameTags;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/** Screen-space projection over world-space block highlights, shared by all client skins and Iris/Sodium pipelines. */
public final class BlockEspRenderer {
    private static final int SHADOW = 0xC8071018;

    private record DrawItem(BlockEsp.Entry entry, AABB box, double distanceSq, float alpha) {}

    private BlockEspRenderer() {}

    public static void render(GuiGraphicsExtractor g) {
        BlockEsp options = Modules.enabled(BlockEsp.class);
        if (options == null || !ModernHudProjection.available()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.gui.hud.isHidden() || mc.gui.overlay() != null) return;

        List<BlockEsp.Entry> entries = options.entries();
        if (entries.isEmpty()) return;

        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        float maxRange = options.getRange();
        double maxRangeSq = (double) maxRange * maxRange;

        List<DrawItem> visible = new ArrayList<>(entries.size());
        for (BlockEsp.Entry entry : entries) {
            var state = level.getBlockState(entry.pos());
            if (!state.is(entry.block())) continue;
            AABB box = entry.box();
            if (entry.partnerPos() != null && !level.getBlockState(entry.partnerPos()).is(entry.block())) {
                var shape = state.getShape(level, entry.pos());
                box = shape.isEmpty() ? new AABB(entry.pos()) : shape.bounds().move(entry.pos());
            }
            double distSq = camPos.distanceToSqr(box.getCenter());
            if (distSq > maxRangeSq * 1.15) continue;
            float distRatio = maxRange > 0F ? (float) (Math.sqrt(distSq) / maxRange) : 0F;
            float alpha = Math.clamp(1.15F - distRatio * 0.45F, 0.45F, 1.0F);
            visible.add(new DrawItem(entry, box, distSq, alpha));
        }
        if (visible.isEmpty()) return;
        visible.sort(Comparator.comparingDouble(DrawItem::distanceSq).reversed());

        Vector3fc fwd = camera.forwardVector();
        Vec3 tracerOrigin = camPos.add(fwd.x() * 0.25, fwd.y() * 0.25, fwd.z() * 0.25);
        BlockEsp.Style style = options.getStyle();
        float thickness = options.getThickness();
        boolean tracers = options.showsTracers();
        boolean labels = options.showsLabels();

        g.nextStratum();
        g.enableScissor(0, 0, g.guiWidth(), g.guiHeight());
        try {
            for (DrawItem item : visible) {
                AABB box = item.box;
                int color = fade(item.entry.color(), item.alpha);
                int shadow = fade(SHADOW, item.alpha * 0.9F);

                if (tracers) {
                    segment(g, tracerOrigin, box.getCenter(), Math.max(0.75F, thickness * 0.85F),
                            fade(color, 0.65F), fade(shadow, 0.65F));
                }

                if (style == BlockEsp.Style.WIREFRAME) {
                    drawWireframeBox(g, box, thickness, color, shadow);
                } else {
                    EspBoxProjection.Bounds bounds = ModernHudProjection.projectBox(box, g.guiWidth(), g.guiHeight());
                    if (bounds != null && bounds.width() >= 1F && bounds.height() >= 1F) {
                        drawProjectedBounds(g, bounds, style == BlockEsp.Style.BOX, color, shadow);
                    }
                }

                if (labels && item.distanceSq <= 48.0 * 48.0) {
                    drawLabel(g, mc, item);
                }
            }
        } finally {
            g.disableScissor();
            g.nextStratum();
        }
    }

    private static void drawWireframeBox(GuiGraphicsExtractor g, AABB b, float thickness, int color, int shadow) {
        Vec3 p000 = new Vec3(b.minX, b.minY, b.minZ);
        Vec3 p100 = new Vec3(b.maxX, b.minY, b.minZ);
        Vec3 p101 = new Vec3(b.maxX, b.minY, b.maxZ);
        Vec3 p001 = new Vec3(b.minX, b.minY, b.maxZ);
        Vec3 p010 = new Vec3(b.minX, b.maxY, b.minZ);
        Vec3 p110 = new Vec3(b.maxX, b.maxY, b.minZ);
        Vec3 p111 = new Vec3(b.maxX, b.maxY, b.maxZ);
        Vec3 p011 = new Vec3(b.minX, b.maxY, b.maxZ);

        // Bottom face
        segment(g, p000, p100, thickness, color, shadow);
        segment(g, p100, p101, thickness, color, shadow);
        segment(g, p101, p001, thickness, color, shadow);
        segment(g, p001, p000, thickness, color, shadow);
        // Top face
        segment(g, p010, p110, thickness, color, shadow);
        segment(g, p110, p111, thickness, color, shadow);
        segment(g, p111, p011, thickness, color, shadow);
        segment(g, p011, p010, thickness, color, shadow);
        // Vertical pillars
        segment(g, p000, p010, thickness, color, shadow);
        segment(g, p100, p110, thickness, color, shadow);
        segment(g, p101, p111, thickness, color, shadow);
        segment(g, p001, p011, thickness, color, shadow);
    }

    private static void drawProjectedBounds(GuiGraphicsExtractor g, EspBoxProjection.Bounds bounds,
                                            boolean fullBox, int color, int shadow) {
        int left = (int) Math.floor(bounds.left());
        int top = (int) Math.floor(bounds.top());
        int right = (int) Math.ceil(bounds.right());
        int bottom = (int) Math.ceil(bounds.bottom());
        int width = right - left;
        int height = bottom - top;
        if (fullBox) {
            rectOutline(g, left, top, right, bottom, 1, shadow);
            rectOutline(g, left, top, right, bottom, 0, color);
        } else {
            int armX = Math.min(width, Math.max(2, Math.min(12, Math.round(width * 0.28F))));
            int armY = Math.min(height, Math.max(2, Math.min(12, Math.round(height * 0.28F))));
            corners(g, left, top, right, bottom, armX, armY, 1, shadow);
            corners(g, left, top, right, bottom, armX, armY, 0, color);
        }
    }

    private static void drawLabel(GuiGraphicsExtractor g, Minecraft mc, DrawItem item) {
        NameTagProjection.Point center = ModernHudProjection.project(
                item.box.getCenter(), g.guiWidth(), g.guiHeight());
        if (center == null || center.x() < 0 || center.y() < 0
                || center.x() > g.guiWidth() || center.y() > g.guiHeight()) return;
        String text = item.entry.block().getName().getString() + " · "
                + NameTags.formatDistance(Math.sqrt(item.distanceSq));
        int textW = mc.font.width(text);
        float scale = 0.75F;
        g.pose().pushMatrix();
        try {
            g.pose().translate(center.x(), center.y());
            g.pose().scale(scale, scale);
            g.text(mc.font, text, -textW / 2, -4, fade(0xFFF0F6FA, item.alpha), true);
        } finally {
            g.pose().popMatrix();
        }
    }

    private static void rectOutline(GuiGraphicsExtractor g, int left, int top, int right, int bottom, int pad, int color) {
        fillPadded(g, left, top, right, top + 1, pad, color);
        fillPadded(g, left, bottom - 1, right, bottom, pad, color);
        fillPadded(g, left, top, left + 1, bottom, pad, color);
        fillPadded(g, right - 1, top, right, bottom, pad, color);
    }

    private static void corners(GuiGraphicsExtractor g, int left, int top, int right, int bottom,
                                int armX, int armY, int pad, int color) {
        fillPadded(g, left, top, left + armX, top + 1, pad, color);
        fillPadded(g, right - armX, top, right, top + 1, pad, color);
        fillPadded(g, left, bottom - 1, left + armX, bottom, pad, color);
        fillPadded(g, right - armX, bottom - 1, right, bottom, pad, color);
        fillPadded(g, left, top, left + 1, top + armY, pad, color);
        fillPadded(g, right - 1, top, right, top + armY, pad, color);
        fillPadded(g, left, bottom - armY, left + 1, bottom, pad, color);
        fillPadded(g, right - 1, bottom - armY, right, bottom, pad, color);
    }

    private static void fillPadded(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int pad, int color) {
        g.fill(x1 - pad, y1 - pad, x2 + pad, y2 + pad, color);
    }

    private static void segment(GuiGraphicsExtractor g, Vec3 from, Vec3 to, float thickness, int color, int shadow) {
        ModernHudProjection.Segment projected = ModernHudProjection.projectSegment(from, to, g.guiWidth(), g.guiHeight());
        if (projected != null) {
            line(g, projected.x1(), projected.y1(), projected.x2(), projected.y2(), thickness + 1.4F, shadow);
            line(g, projected.x1(), projected.y1(), projected.x2(), projected.y2(), thickness, color);
        }
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
        return Math.round((color >>> 24) * Math.clamp(alpha, 0F, 1F)) << 24 | color & 0x00FFFFFF;
    }
}
