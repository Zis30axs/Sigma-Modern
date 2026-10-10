package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.Radar;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One extraction's visual TRK focus. No entity, combat target or module setting is retained or changed. */
public final class ModernTrkFocus {
    private static @Nullable Frame frame;
    private static final float EDGE = 4F;
    private static final float LABEL_HEIGHT = 18F;

    record Marker(float left, float top, float right, float bottom) {
        float width() { return right - left; }
        float height() { return bottom - top; }
    }

    record Label(float x, float y, float width, float height) {}

    record Frame(int entityId, UUID uuid, Radar.Focus policy, float others,
                 Marker marker, Radar.Style style, double distance, float pulse) {
        boolean targets(int id, UUID candidate) { return entityId == id && uuid.equals(candidate); }

        float opacity(int id, UUID candidate) {
            return targets(id, candidate) ? 1F : policy == Radar.Focus.TARGET_ONLY ? 0F : others;
        }
    }

    private ModernTrkFocus() {}

    /** Always called, including menu-only frames, so no projection or focus survives the frame that produced it. */
    public static void beginFrame(GuiGraphicsExtractor g, boolean renderLevel) {
        frame = null;
        if (!renderLevel) return;
        Minecraft mc = Minecraft.getInstance();
        Radar radar = Modules.enabled(Radar.class);
        if (radar == null || radar.getTrkFocus() == Radar.Focus.OFF || mc.level == null || mc.player == null
                || !mc.player.isAlive() || mc.player.isSpectator() || mc.gui.hud.isHidden()
                || mc.gui.screen() != null || mc.gui.overlay() != null || !ModernHud.isActive()
                || !ModernHudProjection.available()) return;

        LivingEntity target = RadarContacts.trackedTarget(mc, radar);
        if (target == null || target == mc.player || target == mc.getCameraEntity()) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 position = target.getPosition(partial);
        AABB box = target.getBoundingBox().move(position.subtract(target.position()));
        Marker marker = project(box, g.guiWidth(), g.guiHeight());
        double distance = position.distanceTo(mc.player.getPosition(partial));
        float pulse = (float) Math.sin((mc.level.getGameTime() % 24_000L + partial) * Math.PI / 24);
        frame = snapshot(radar.getTrkFocus(), true, target.getId(), target.getUUID(), marker,
                radar.getStyle(), distance, radar.getOtherOpacity(), pulse);
    }

    /** Shared by NameTags, ESP and Aura, including all of their decoration and equipment layers. */
    public static float opacity(int entityId, UUID uuid) {
        return frame == null ? 1F : frame.opacity(entityId, uuid);
    }

    /** A regular target marker is replaced only when this frame has a drawable dedicated TRK marker. */
    public static boolean replacesMarker(int entityId, UUID uuid) {
        return frame != null && frame.targets(entityId, uuid);
    }

    public static void render(GuiGraphicsExtractor g) {
        Frame current = frame;
        if (current == null) return;
        Marker marker = current.marker;
        boolean wt = current.style == Radar.Style.WT;
        int ink = RadarScopeDrawing.ink(wt);
        if (!wt) ink = ModernTypography.fade(ink, 0.88F + current.pulse * 0.1F);
        String text = RadarScopeDrawing.fit(wt, "TRK  " + Math.round(current.distance) + " m", g.guiWidth() - 20);
        Label label = label(marker, RadarScopeDrawing.width(wt, text), g.guiWidth(), g.guiHeight());
        g.nextStratum();
        g.enableScissor(0, 0, g.guiWidth(), g.guiHeight());
        try {
            if (wt) {
                outline(g, marker, 4F, 0xBC031008);
                outline(g, marker, 1.8F, ink);
            } else {
                corners(g, marker, 6F, ModernTypography.fade(ink, 0.12F));
                corners(g, marker, 4F, 0xDA08151F);
                corners(g, marker, 2F, ink);
                for (float x : new float[] {marker.left, marker.right}) {
                    for (float y : new float[] {marker.top, marker.bottom}) {
                        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, x - 12, y - 12, 24,
                                ModernTypography.fade(ink, 0.25F));
                    }
                }
            }
            if (wt) {
                ModernStyle.fill(g, Math.round(label.x), Math.round(label.y), Math.round(label.x + label.width),
                        Math.round(label.y + label.height), 0xBE031108);
                RadarScopeDrawing.line(g, label.x, label.y + label.height, label.x + label.width,
                        label.y + label.height, 1F, ink);
            } else {
                ModernStyle.rounded(g, Math.round(label.x), Math.round(label.y), Math.round(label.width),
                        Math.round(label.height), 4, 0xDA081B27);
            }
            RadarScopeDrawing.text(g, wt, text, label.x + 6, label.y + 4, ink);
        } finally {
            g.disableScissor();
        }
        g.nextStratum();
    }

    static @Nullable Frame snapshot(Radar.Focus policy, boolean tracking, int entityId, @Nullable UUID uuid,
                                    @Nullable Marker marker, Radar.Style style, double distance,
                                    float others, float pulse) {
        if (policy == Radar.Focus.OFF || !tracking || entityId < 0 || uuid == null || marker == null
                || !Double.isFinite(distance) || distance < 0) return null;
        return new Frame(entityId, uuid, policy, Float.isFinite(others) ? Math.clamp(others, 0F, 1F) : 0.2F,
                marker, style, distance, Float.isFinite(pulse) ? Math.clamp(pulse, -1F, 1F) : 0F);
    }

    /** Keep the frame centered on the actual body; never replace a behind-camera or offscreen target with an edge arrow. */
    static @Nullable Marker project(AABB box, int width, int height) {
        if (width < 40 || height < 60) return null;
        EspBoxProjection.Bounds bounds = ModernHudProjection.projectBox(box, width, height);
        NameTagProjection.Point anchor = ModernHudProjection.project(box.getCenter(), width, height);
        if (bounds == null || anchor == null || anchor.x() < EDGE || anchor.x() > width - EDGE
                || anchor.y() < EDGE || anchor.y() > height - EDGE) return null;
        float halfWidth = Math.clamp(bounds.width() / 2F + 5F, 14F, Math.min(72F, (width - 2 * EDGE) / 2F));
        float halfHeight = Math.clamp(bounds.height() / 2F + 5F, 19F, Math.min(90F, (height - 2 * EDGE) / 2F));
        return new Marker(Math.max(EDGE, anchor.x() - halfWidth), Math.max(EDGE, anchor.y() - halfHeight),
                Math.min(width - EDGE, anchor.x() + halfWidth), Math.min(height - EDGE, anchor.y() + halfHeight));
    }

    static Label label(Marker marker, float textWidth, int width, int height) {
        float labelWidth = Math.clamp(textWidth + 12F, 12F, width - 2 * EDGE);
        float x = Math.clamp((marker.left + marker.right - labelWidth) / 2F, EDGE, width - EDGE - labelWidth);
        // NameTags sit above the head; prefer a separate caption below the body before using an edge fallback.
        float y = marker.bottom + 5F;
        if (y + LABEL_HEIGHT > height - EDGE) y = marker.top - LABEL_HEIGHT - 5F;
        if (y < EDGE) y = marker.top + 5F;
        return new Label(x, Math.clamp(y, EDGE, height - EDGE - LABEL_HEIGHT), labelWidth, LABEL_HEIGHT);
    }

    private static void outline(GuiGraphicsExtractor g, Marker m, float thickness, int color) {
        RadarScopeDrawing.line(g, m.left, m.top, m.right, m.top, thickness, color);
        RadarScopeDrawing.line(g, m.right, m.top, m.right, m.bottom, thickness, color);
        RadarScopeDrawing.line(g, m.right, m.bottom, m.left, m.bottom, thickness, color);
        RadarScopeDrawing.line(g, m.left, m.bottom, m.left, m.top, thickness, color);
    }

    private static void corners(GuiGraphicsExtractor g, Marker m, float thickness, int color) {
        float arm = Math.min(22F, Math.min(m.width(), m.height()) * 0.3F);
        for (int side = 0; side < 2; side++) {
            float x = side == 0 ? m.left : m.right;
            float dx = side == 0 ? arm : -arm;
            RadarScopeDrawing.line(g, x, m.top, x + dx, m.top, thickness, color);
            RadarScopeDrawing.line(g, x, m.top, x, m.top + arm, thickness, color);
            RadarScopeDrawing.line(g, x, m.bottom, x + dx, m.bottom, thickness, color);
            RadarScopeDrawing.line(g, x, m.bottom, x, m.bottom - arm, thickness, color);
        }
    }
}
