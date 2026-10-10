package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.render.Esp;
import com.mentalfrostbyte.jello.module.impl.render.NameTags;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Frame-local player outlines, drawn above the world without changing entity rendering or visibility. */
public final class ModernEsp {
    private static final int SHADOW = 0xD90A1520;
    private record Snapshot(EspBoxProjection.Bounds bounds, float health, double distanceSquared, int color,
                            float opacity) {}

    private ModernEsp() {}

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        Esp options = Modules.enabled(Esp.class);
        if (options == null || mc.level == null || mc.player == null || mc.gui.hud.isHidden()
                || mc.gui.overlay() != null || !ModernHud.isActive() || !ModernHudEditor.allowsHud()
                || !ModernHudProjection.available()) return;

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 origin = mc.player.getPosition(partial);
        List<Snapshot> players = new ArrayList<>();
        for (AbstractClientPlayer player : mc.level.players()) {
            Vec3 position = player.getPosition(partial);
            double distanceSquared = position.distanceToSqr(origin);
            if (!options.accepts(new Esp.Candidate(player == mc.player || player == mc.getCameraEntity(),
                    player.level() == mc.level, player.isAlive(), player.isRemoved(), player.isSpectator(),
                    player.isInvisible(), distanceSquared))) continue;
            // The shared TRK marker owns the locked target's outline; preserve this module's settings.
            if (ModernTrkFocus.replacesMarker(player.getId(), player.getUUID())) continue;
            float opacity = ModernTrkFocus.opacity(player.getId(), player.getUUID());
            if (opacity <= 0F) continue;
            // Use the current pose's dimensions, translated to the same partial tick as the world renderer.
            AABB box = player.getBoundingBox().move(position.subtract(player.position()));
            EspBoxProjection.Bounds bounds = ModernHudProjection.projectBox(box, g.guiWidth(), g.guiHeight());
            if (bounds == null || bounds.width() < 1F || bounds.height() < 2F) continue;
            float health = NameTags.healthFraction(player.getHealth(), player.getMaxHealth());
            players.add(new Snapshot(bounds, health, distanceSquared, options.outlineColor(health), opacity));
        }
        if (players.isEmpty()) return;
        players.sort(Comparator.comparingDouble(Snapshot::distanceSquared).reversed());
        g.nextStratum();
        g.enableScissor(0, 0, g.guiWidth(), g.guiHeight());
        try {
            for (Snapshot player : players) {
                try (var ignored = ModernStyle.alphaScope(player.opacity)) {
                    draw(g, player, options);
                }
            }
        } finally {
            g.disableScissor();
        }
        g.nextStratum();
    }

    private static void draw(GuiGraphicsExtractor g, Snapshot player, Esp options) {
        EspBoxProjection.Bounds bounds = player.bounds;
        int left = (int) Math.floor(bounds.left());
        int top = (int) Math.floor(bounds.top());
        int right = (int) Math.ceil(bounds.right());
        int bottom = (int) Math.ceil(bounds.bottom());
        int width = right - left;
        int height = bottom - top;
        if (options.getStyle() == Esp.Style.BOX) {
            rectangle(g, left, top, right, bottom, 1, SHADOW);
            rectangle(g, left, top, right, bottom, 0, player.color);
        } else {
            int armX = Math.min(width, Math.max(2, Math.min(14, Math.round(width * 0.25F))));
            int armY = Math.min(height, Math.max(2, Math.min(14, Math.round(height * 0.2F))));
            corners(g, left, top, right, bottom, armX, armY, 1, SHADOW);
            corners(g, left, top, right, bottom, armX, armY, 0, player.color);
        }
        if (options.showsHealthBar() && height >= 5 && left >= 5) {
            ModernStyle.fill(g, left - 5, top - 1, left - 1, bottom + 1, SHADOW);
            ModernStyle.fill(g, left - 4, top, left - 2, bottom, 0xFF283A45);
            int filled = Math.round(height * player.health);
            if (filled > 0) ModernStyle.fill(g, left - 4, bottom - filled, left - 2, bottom,
                    NameTags.healthColor(player.health));
        }
        if (options.showsDistance()) {
            String label = NameTags.formatDistance(Math.sqrt(player.distanceSquared));
            float scale = 0.7F;
            float labelWidth = ModernTypography.width(label) * scale;
            // Labels use only spare space beneath the box; screen-edge clipping never moves them onto the player.
            if (bottom + 12 <= g.guiHeight()) {
                float x = Math.clamp((left + right - labelWidth) * 0.5F, 1F,
                        Math.max(1F, g.guiWidth() - labelWidth - 1F));
                ModernTypography.draw(g, label, x, bottom + 3F, scale, 0xFFF0F6FA, true);
            }
        }
    }

    private static void rectangle(GuiGraphicsExtractor g, int left, int top, int right, int bottom,
                                  int pad, int color) {
        segment(g, left, top, right, top + 1, pad, color);
        segment(g, left, bottom - 1, right, bottom, pad, color);
        segment(g, left, top, left + 1, bottom, pad, color);
        segment(g, right - 1, top, right, bottom, pad, color);
    }

    private static void corners(GuiGraphicsExtractor g, int left, int top, int right, int bottom,
                                int armX, int armY, int pad, int color) {
        segment(g, left, top, left + armX, top + 1, pad, color);
        segment(g, right - armX, top, right, top + 1, pad, color);
        segment(g, left, bottom - 1, left + armX, bottom, pad, color);
        segment(g, right - armX, bottom - 1, right, bottom, pad, color);
        segment(g, left, top, left + 1, top + armY, pad, color);
        segment(g, right - 1, top, right, top + armY, pad, color);
        segment(g, left, bottom - armY, left + 1, bottom, pad, color);
        segment(g, right - 1, bottom - armY, right, bottom, pad, color);
    }

    private static void segment(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int pad, int color) {
        ModernStyle.fill(g, x1 - pad, y1 - pad, x2 + pad, y2 + pad, color);
    }
}
