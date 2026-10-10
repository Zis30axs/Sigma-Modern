package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.render.NameTags;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * Modern's player labels, extracted from the same visible entities as vanilla, then drawn below the HUD.
 * The frame starts before level extraction and ends before GUI extraction: inventory previews cannot
 * contribute labels, and neither live entities nor last frame's camera matrix are retained for drawing.
 */
public final class ModernNameTags {
    private static final String ICONS = "/assets/minecraft/sigma/icons/nametags/";
    private static final int MAX_TAGS = 256;
    private static final float META_SCALE = 0.75F;
    private static final int GOLD = 0xFFF1CF86;
    private static final int SUSPECT = 0xFFFF5555;
    private static final List<Tag> TAGS = new ArrayList<>();
    private static final Matrix4f WORLD_TO_CLIP = new Matrix4f();
    private static Vec3 cameraPos = Vec3.ZERO;
    private static @Nullable NameTags settings;
    private static boolean collecting;

    private record Tag(int entityId, UUID uuid, Vec3 anchor, double distance, Identifier skin, boolean hat,
                       String name, int nameColor, boolean suspect, String score,
                       float health, float maxHealth, float absorption, int ping) {}

    private record Metric(String icon, String text, int color) {}

    private ModernNameTags() {}

    /** Called once per frame, with the exact projection * camera effects * view rotation used for the world. */
    public static void beginFrame(@Nullable CameraRenderState camera, @Nullable Matrix4fc worldToClip) {
        TAGS.clear();
        settings = null;
        collecting = false;
        Minecraft mc = Minecraft.getInstance();
        if (camera == null || worldToClip == null || mc.player == null || mc.level == null
                || mc.gui.hud.isHidden() || !ModernHud.isActive() || camera.isPanoramicMode
                || camera.isFrustumCaptured) return;
        settings = Modules.enabled(NameTags.class);
        if (settings == null) return;
        cameraPos = camera.pos;
        WORLD_TO_CLIP.set(worldToClip);
        collecting = true;
    }

    public static void finishFrame() {
        collecting = false;
    }

    /**
     * Called after a world entity's render state is complete. Respect vanilla/team/Sodium visibility
     * and the existing distance limit; only remove the old label after its replacement is captured.
     */
    public static void capture(Entity entity, EntityRenderState state, Vec3 renderOffset) {
        if (!collecting || settings == null || TAGS.size() >= MAX_TAGS
                || !(entity instanceof AbstractClientPlayer player)
                || state.nameTag == null || state.nameTagAttachment == null || state.isDiscrete) return;
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.getCameraEntity() || !player.isAlive() || player.isSpectator()
                || player.level() != mc.level) return;
        Vec3 origin = new Vec3(state.x, state.y, state.z).add(renderOffset);
        double distance = origin.distanceTo(cameraPos);
        if (!Double.isFinite(distance) || distance > settings.getRange()) return;
        Vec3 anchor = origin.add(state.nameTagAttachment).add(0, 0.25, 0);
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player.getUUID());
        int teamColor = player.getTeamColor();
        int nameColor = state.sigmaSuspectNameTag ? SUSPECT : teamColor == 0xFFFFFF ? ModernStyle.TEXT
                : ModernStyle.mix(ModernStyle.TEXT, 0xFF000000 | teamColor, 0.65F);
        TAGS.add(new Tag(player.getId(), player.getUUID(), anchor, distance, player.getSkin().body().texturePath(),
                player.isModelPartShown(PlayerModelPart.HAT), NameTags.boundedText(player.getDisplayName().getString(), 64),
                nameColor, state.sigmaSuspectNameTag, state.scoreText == null ? "" : NameTags.boundedText(state.scoreText.getString(), 64),
                player.getHealth(), player.getMaxHealth(), player.getAbsorptionAmount(), info == null ? -1 : info.getLatency()));
        state.nameTag = null;
        state.scoreText = null;
    }

    /** Nearer players paint over farther labels; an active TRK target always paints last. */
    public static void render(GuiGraphicsExtractor g) {
        if (settings == null || TAGS.isEmpty()) return;
        TAGS.sort(Comparator.comparing((Tag tag) -> ModernTrkFocus.replacesMarker(tag.entityId, tag.uuid))
                .thenComparing(Comparator.comparingDouble(Tag::distance).reversed()));
        for (Tag tag : TAGS) {
            float opacity = ModernTrkFocus.opacity(tag.entityId, tag.uuid);
            if (opacity <= 0F) continue;
            Vec3 relative = tag.anchor.subtract(cameraPos);
            NameTagProjection.Point point = NameTagProjection.project(WORLD_TO_CLIP,
                    relative.x, relative.y, relative.z, g.guiWidth(), g.guiHeight());
            if (point != null) {
                try (var ignored = ModernStyle.alphaScope(opacity)) {
                    draw(g, tag, point, settings);
                }
            }
        }
        g.nextStratum();
    }

    private static void draw(GuiGraphicsExtractor g, Tag tag, NameTagProjection.Point point, NameTags options) {
        List<Metric> metrics = new ArrayList<>(4);
        int healthColor = NameTags.healthColor(NameTags.healthFraction(tag.health, tag.maxHealth));
        if (options.showsHealth()) {
            metrics.add(new Metric("heart", NameTags.formatHealth(tag.health), healthColor));
            if (Float.isFinite(tag.absorption) && tag.absorption > 0)
                metrics.add(new Metric("shield", "+" + NameTags.formatHealth(tag.absorption), GOLD));
        }
        if (options.showsDistance()) metrics.add(new Metric("distance", NameTags.formatDistance(tag.distance), ModernStyle.MUTED));
        if (options.showsPing()) metrics.add(new Metric("signal", NameTags.formatPing(tag.ping), tag.ping >= 200 ? GOLD : ModernStyle.MUTED));
        String name = ModernTypography.ellipsize(tag.name, 128);
        int contentX = 6 + (options.showsAvatar() ? 18 : 0) + (tag.suspect ? 12 : 0);
        int metricWidth = 0;
        for (Metric metric : metrics) metricWidth += metricWidth(metric) + 6;
        String score = options.showsScore() ? ModernTypography.ellipsize(tag.score, 192) : "";
        int width = Math.max(contentX + ModernTypography.width(name) + metricWidth + 6,
                Math.round(ModernTypography.width(score) * META_SCALE) + 12);
        width = Math.max(24, width);
        int height = 21 + (options.showsHealthBar() ? 4 : 0) + (score.isEmpty() ? 0 : 11);
        float scale = options.getScale();
        float x = point.x() - width * scale / 2F;
        float y = point.y() - height * scale - 3F;
        if (x + width * scale < 0 || x > g.guiWidth() || y + height * scale < 0 || y > g.guiHeight()) return;
        // Constant scale avoids continuously rerasterizing glyphs/SVGs as players change distance.
        g.nextStratum();
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, y);
            g.pose().scale(scale, scale);
            ModernStyle.rounded(g, -1, 1, width + 2, height + 1, 5, 0x3002070D);
            ModernStyle.rounded(g, 0, 0, width, height, 4, 0x468AB9CF);
            ModernStyle.rounded(g, 1, 1, width - 2, height - 2, 3, Math.round(options.getBackground() * 255) << 24 | 0x0C1C29);
            if (options.showsAvatar()) {
                PlayerFaceExtractor.extractRenderState(g, tag.skin, 5, 4, 14, tag.hat, false, ModernStyle.a(0xFFFFFFFF));
            }
            if (tag.suspect) {
                ModernSvg.mask(g, ICONS + "suspect.svg", contentX - 12F, 6F, 9, 9, SUSPECT);
            }
            ModernTypography.draw(g, name, contentX, 5, tag.nameColor, false);
            int metricX = contentX + ModernTypography.width(name) + 6;
            for (Metric metric : metrics) {
                ModernSvg.mask(g, ICONS + metric.icon + ".svg", metricX, 7F, 7, 7, metric.color);
                ModernTypography.draw(g, metric.text, metricX + 9F, 7F, META_SCALE, metric.color, false);
                metricX += metricWidth(metric) + 6;
            }
            if (options.showsHealthBar()) {
                int trackWidth = width - 12;
                ModernStyle.rounded(g, 6, 21, trackWidth, 2, 1, 0x25FFFFFF);
                int goldWidth = Float.isFinite(tag.absorption) && tag.absorption > 0
                        ? Math.round(trackWidth * NameTags.healthFraction(tag.absorption, tag.maxHealth + tag.absorption)) : 0;
                int fill = Math.round((trackWidth - goldWidth) * NameTags.healthFraction(tag.health, tag.maxHealth));
                if (fill > 0) ModernStyle.rounded(g, 6, 21, fill, 2, 1, healthColor);
                if (goldWidth > 0) {
                    ModernStyle.rounded(g, 6 + trackWidth - goldWidth, 21, goldWidth, 2, 1, GOLD);
                }
            }
            if (!score.isEmpty()) ModernTypography.draw(g, score,
                    (width - ModernTypography.width(score) * META_SCALE) / 2F, height - 11F, META_SCALE, ModernStyle.MUTED, false);
        } finally {
            g.pose().popMatrix();
        }
    }

    private static int metricWidth(Metric metric) {
        return 9 + Math.round(ModernTypography.width(metric.text) * META_SCALE);
    }
}
