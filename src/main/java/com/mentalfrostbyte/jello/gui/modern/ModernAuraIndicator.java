package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.combat.KillAura;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** A world-anchored lock-on marker for Aura's current selection, with no retained target or HUD panel. */
public final class ModernAuraIndicator {
    record Marker(float x, float y, float radius) {}

    private ModernAuraIndicator() {}

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        KillAura aura = Modules.enabled(KillAura.class);
        if (aura == null || !aura.showsTargetIndicator() || mc.level == null || mc.player == null
                || !mc.player.isAlive() || mc.player.isSpectator() || mc.gui.hud.isHidden()
                || mc.gui.screen() != null || mc.gui.overlay() != null || !ModernHud.isActive()
                || !ModernHudProjection.available()) return;

        // Never borrow TargetHUD's recent-hit/crosshair fallback; changing targets takes effect this frame.
        LivingEntity target = aura.getTarget();
        if (target == null || target == mc.player || target == mc.getCameraEntity()
                || target.level() != mc.level || !target.isAlive() || target.isRemoved() || target.isSpectator()) return;
        // A visible TRK frame already marks this entity; another Aura target retains its own indicator.
        if (ModernTrkFocus.replacesMarker(target.getId(), target.getUUID())) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 position = target.getPosition(partial);
        AABB box = target.getBoundingBox().move(position.subtract(target.position()));
        Marker marker = project(box, g.guiWidth(), g.guiHeight());
        if (marker == null) return;

        // Game time keeps the small breathing/sway animation continuous across target switches.
        double seconds = (mc.level.getGameTime() % 24_000L + partial) / 20.0;
        float pulse = (float) Math.sin(seconds * Math.PI * 2 / 2.4);
        float scale = marker.radius / 18F * (1F + pulse * 0.045F);
        int color = aura.getIndicatorColor();
        g.nextStratum();
        g.enableScissor(0, 0, g.guiWidth(), g.guiHeight());
        g.pose().pushMatrix();
        try {
            g.pose().translate(marker.x, marker.y);
            g.pose().rotate((float) (Math.PI / 4 + Math.sin(seconds * 1.2) * 0.18));
            g.pose().scale(scale, scale);
            for (int corner = 0; corner < 4; corner++) {
                ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, -26, -26, 18, 0x50000000 | (color & 0xFFFFFF));
                ModernStyle.rounded(g, -19, -19, 12, 4, 2, 0xC00A1520);
                ModernStyle.rounded(g, -19, -19, 4, 12, 2, 0xC00A1520);
                ModernStyle.rounded(g, -18, -18, 10, 2, 1, color);
                ModernStyle.rounded(g, -18, -18, 2, 10, 1, color);
                g.pose().rotate((float) (Math.PI / 2));
            }
        } finally {
            g.pose().popMatrix();
            g.disableScissor();
        }
        g.nextStratum();
    }

    /** Keep the marker on the actual body anchor; never clamp an offscreen target onto the screen edge. */
    static @Nullable Marker project(AABB box, int width, int height) {
        EspBoxProjection.Bounds bounds = ModernHudProjection.projectBox(box, width, height);
        if (bounds == null) return null;
        NameTagProjection.Point anchor = ModernHudProjection.project(box.getCenter(), width, height);
        if (anchor == null || anchor.x() < 0 || anchor.x() >= width || anchor.y() < 0 || anchor.y() >= height) return null;
        // A close or unusually large entity must not turn the marker into a full-screen overlay.
        float radius = Math.clamp(Math.min(bounds.width(), bounds.height()) * 0.35F, 14F, 26F);
        return new Marker(anchor.x(), anchor.y(), radius);
    }
}
