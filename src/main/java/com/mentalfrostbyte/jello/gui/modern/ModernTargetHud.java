package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.TargetHud;
import com.mentalfrostbyte.jello.module.impl.render.NameTags;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The current combat target as a movable glass card; only copied frame data reaches its drawing code. */
public final class ModernTargetHud {
    private static final int WIDTH = 216;
    private static final int GOLD = 0xFFF1CF86;
    private static final String ICONS = "/assets/minecraft/sigma/icons/nametags/";
    private static final EquipmentSlot[] EQUIPMENT = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    private static final HealthAnimation HEALTH = new HealthAnimation();

    private record Gear(ItemStack stack, float durability) {}
    private record Snapshot(UUID id, String name, @Nullable Identifier skin, boolean hat, boolean player,
                            boolean preview, float health, float maximum, float absorption, float hurt,
                            double distance, int ping, Vec3 anchor, List<Gear> gear) {}

    private ModernTargetHud() {}

    public static void render(final GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        TargetHud options = Modules.enabled(TargetHud.class);
        boolean editing = ModernHudEditor.isEditing();
        if (options == null || mc.player == null || mc.level == null || (mc.gui.hud.isHidden() && !editing)
                || !ModernHud.isActive() || !ModernHudEditor.allowsHud()) {
            HEALTH.clear();
            return;
        }
        LivingEntity target = options.getTarget(editing);
        if (target == null) {
            HEALTH.clear();
            return;
        }
        Snapshot data = snapshot(mc, target, options);
        int height = panelHeight(options.showsArmor(), editing, data.gear.size());
        float scale = fittingScale(options.getScale(), g.guiWidth(), g.guiHeight(), height);
        if (scale <= 0F) return;
        float width = WIDTH * scale;
        float scaledHeight = height * scale;
        float defaultX;
        float defaultY;
        boolean following = options.getMode() == TargetHud.Mode.FOLLOW_TARGET;
        if (following) {
            NameTagProjection.Point anchor = editing
                    ? new NameTagProjection.Point(g.guiWidth() * 0.5F + 20F, g.guiHeight() * 0.5F)
                    : ModernHudProjection.project(data.anchor, g.guiWidth(), g.guiHeight());
            if (!onScreen(anchor, g.guiWidth(), g.guiHeight())) {
                HEALTH.clear();
                return;
            }
            defaultX = anchor.x() + 18F;
            defaultY = anchor.y() - scaledHeight * 0.5F;
        } else {
            defaultX = (g.guiWidth() - width) * 0.5F + 24F;
            defaultY = g.guiHeight() * 0.62F;
        }
        var bounds = following
                ? ModernHudEditor.placeFollowing(g, "target_hud_follow", "TargetHUD · Follow offset", defaultX, defaultY, width, scaledHeight)
                : ModernHudEditor.place(g, "target_hud", "TargetHUD", defaultX, defaultY, width, scaledHeight);
        Bars bars = HEALTH.update(data.id, options.getSession(), data.health, data.maximum, data.absorption, System.nanoTime());
        g.nextStratum();
        g.pose().pushMatrix();
        try {
            g.pose().translate(bounds.x(), bounds.y());
            g.pose().scale(scale, scale);
            draw(g, data, options, bars, height);
        } finally {
            g.pose().popMatrix();
        }
        g.nextStratum();
    }

    private static Snapshot snapshot(final Minecraft mc, final LivingEntity target, final TargetHud options) {
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Identifier skin = null;
        boolean hat = false;
        boolean player = target instanceof AbstractClientPlayer;
        if (target instanceof AbstractClientPlayer clientPlayer) {
            skin = clientPlayer.getSkin().body().texturePath();
            hat = clientPlayer.isModelPartShown(PlayerModelPart.HAT);
        }
        PlayerInfo info = player && mc.getConnection() != null ? mc.getConnection().getPlayerInfo(target.getUUID()) : null;
        List<Gear> gear = new ArrayList<>(6);
        if (options.showsArmor()) {
            for (EquipmentSlot slot : EQUIPMENT) {
                ItemStack stack = target.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    ItemStack copy = stack.copy();
                    gear.add(new Gear(copy, copy.isDamageableItem()
                            ? durability(copy.getDamageValue(), copy.getMaxDamage()) : -1F));
                }
            }
        }
        Vec3 position = target.getPosition(partial);
        return new Snapshot(target.getUUID(), NameTags.boundedText(target.getDisplayName().getString(), 64), skin, hat, player,
                target == mc.player, target.getHealth(), target.getMaxHealth(), target.getAbsorptionAmount(),
                Math.clamp((target.hurtTime - partial) / 10F, 0F, 1F),
                mc.player.getPosition(partial).distanceTo(position), info == null ? -1 : info.getLatency(),
                position.add(0.0, target.getBbHeight() * 0.6, 0.0), List.copyOf(gear));
    }

    private static void draw(final GuiGraphicsExtractor g, final Snapshot data, final TargetHud options,
                             final Bars bars, final int height) {
        int healthColor = NameTags.healthColor(NameTags.healthFraction(data.health, data.maximum));
        ModernStyle.dropShadow(g, 0, 2, WIDTH, height, 10, 0.65F);
        ModernStyle.rounded(g, 0, 0, WIDTH, height, 10, 0x668BBFD9);
        ModernStyle.rounded(g, 1, 1, WIDTH - 2, height - 2, 9,
                Math.round(options.getBackground() * 255) << 24 | 0x0A1926);
        ModernStyle.fillGradient(g, 12, 1, WIDTH - 12, 16, 0x23C3EFFF, 0x00C3EFFF);
        ModernStyle.rounded(g, 10, 17, 42, 42, 8, 0xFF29495B);
        if (data.skin != null) {
            int tint = ModernStyle.mix(0xFFFFFFFF, 0xFFFF8092, data.hurt * 0.6F);
            PlayerFaceExtractor.extractRenderState(g, data.skin, 13, 20, 36, data.hat, false, tint);
        } else {
            ModernIcons.draw(g, ModernIcons.Icon.PERSON, 17, 24, 28, ModernStyle.ACCENT);
        }
        ModernTypography.draw(g, data.preview ? "PREVIEW" : "TARGET", 63F, 8F, 0.6F, ModernStyle.MUTED, false);
        ModernTypography.draw(g, ModernTypography.ellipsize(data.name, 141), 62, 19, ModernStyle.TEXT, false);
        ModernSvg.mask(g, ICONS + "heart.svg", 62, 35, 8, 8, healthColor);
        String health = NameTags.formatHealth(data.health) + " / " + NameTags.formatHealth(data.maximum);
        ModernTypography.draw(g, health, 74F, 34F, 0.82F, ModernStyle.TEXT, false);

        int trackWidth = WIDTH - 74;
        ModernStyle.rounded(g, 62, 51, trackWidth, 5, 2, 0x2FFFFFFF);
        int trail = Math.round(trackWidth * bars.trail);
        int fill = Math.round(trackWidth * bars.health);
        int absorption = Math.round(trackWidth * bars.absorption);
        if (trail > 0) ModernStyle.rounded(g, 62, 51, trail, 5, 2, 0xA6EF7F8E);
        if (fill > 0) {
            ModernStyle.rounded(g, 62, 51, fill, 5, 2, healthColor);
            ModernStyle.fillGradient(g, 64, 51, 62 + Math.max(2, fill - 2), 53, 0x45FFFFFF, 0x00FFFFFF);
        }
        if (absorption > 0) ModernStyle.rounded(g, 62 + trackWidth - absorption, 51, absorption, 5, 2, GOLD);

        ModernStyle.fill(g, 12, 64, WIDTH - 12, 65, 0x16C9EDFF);
        int metricX = 12;
        metricX += metric(g, "distance", NameTags.formatDistance(data.distance), metricX, ModernStyle.MUTED) + 12;
        if (options.showsPing() && data.player) {
            metricX += metric(g, "signal", NameTags.formatPing(data.ping), metricX,
                    data.ping >= 200 ? GOLD : ModernStyle.MUTED) + 12;
        }
        if (Float.isFinite(data.absorption) && data.absorption > 0F) {
            metric(g, "shield", "+" + NameTags.formatHealth(data.absorption), metricX, GOLD);
        }
        if (!data.gear.isEmpty()) {
            drawEquipment(g, data.gear);
        } else if (height > 88) {
            // Reserve empty equipment slots in the editor so acquiring a target cannot resize a drag.
            for (int slot = 0, x = (WIDTH - 164) / 2; slot < 6; slot++, x += 28) {
                ModernStyle.rounded(g, x, 91, 24, 24, 4, 0x202F5065);
                ModernTypography.draw(g, "—", x + 8F, 99F, 0.75F, 0x658DAABD, false);
            }
        }
    }

    private static int metric(final GuiGraphicsExtractor g, final String icon, final String text, final int x, final int color) {
        ModernSvg.mask(g, ICONS + icon + ".svg", x, 71, 8, 8, color);
        ModernTypography.draw(g, text, x + 11F, 70F, 0.75F, color, false);
        return 11 + Math.round(ModernTypography.width(text) * 0.75F);
    }

    private static void drawEquipment(final GuiGraphicsExtractor g, final List<Gear> gear) {
        int cellsWidth = gear.size() * 28 - 4;
        int x = (WIDTH - cellsWidth) / 2;
        for (Gear item : gear) {
            ModernStyle.rounded(g, x, 91, 24, 24, 4, 0x382F5065);
            // The copied stack has no local-player cooldown overlay or live entity attached to it.
            g.fakeItem(item.stack, x + 4, 92);
            if (item.durability >= 0F) {
                String remaining = Math.round(item.durability * 100F) + "%";
                float textWidth = ModernTypography.width(remaining) * 0.55F;
                ModernTypography.draw(g, remaining, x + (24F - textWidth) / 2F, 109F, 0.55F,
                        NameTags.healthColor(item.durability), false);
            } else if (item.stack.getCount() > 1) {
                String count = item.stack.getCount() > 999 ? "999+" : Integer.toString(item.stack.getCount());
                float textWidth = ModernTypography.width(count) * 0.55F;
                ModernTypography.draw(g, count, x + (24F - textWidth) / 2F, 109F, 0.55F, ModernStyle.MUTED, false);
            }
            x += 28;
        }
    }

    static boolean onScreen(final NameTagProjection.@Nullable Point point, final int width, final int height) {
        return point != null && Float.isFinite(point.x()) && Float.isFinite(point.y())
                && point.x() >= 0F && point.y() >= 0F && point.x() < width && point.y() < height;
    }

    static int panelHeight(final boolean armor, final boolean editing, final int equippedItems) {
        return armor && (editing || equippedItems > 0) ? 122 : 88;
    }

    static float fittingScale(final float requested, final int width, final int height, final int panelHeight) {
        if (!Float.isFinite(requested) || requested <= 0F || width <= 0 || height <= 0 || panelHeight <= 0) return 0F;
        return Math.max(0F, Math.min(requested, Math.min(Math.max(0, width - 12) / (float) WIDTH,
                Math.max(0, height - 12) / (float) panelHeight)));
    }

    static float durability(final int damage, final int maximum) {
        return maximum <= 0 ? 0F : (float) Math.clamp(1.0 - (double) damage / maximum, 0.0, 1.0);
    }

    record Bars(float health, float trail, float absorption) {}

    /** Value-only animation state: changing targets/sessions never animates from another player's health. */
    static final class HealthAnimation {
        private @Nullable UUID target;
        private long session;
        private long previous;
        private float health;
        private float trail;
        private float absorption;

        Bars update(final UUID id, final long generation, final float current, final float maximum,
                    final float extra, final long now) {
            double max = Float.isFinite(maximum) && maximum > 0F ? maximum : 0.0;
            double shield = Float.isFinite(extra) && extra > 0F ? extra : 0.0;
            float targetAbsorption = max + shield > 0.0 ? (float) (shield / (max + shield)) : 0F;
            float targetHealth = (1F - targetAbsorption) * NameTags.healthFraction(current, maximum);
            long elapsed = now - this.previous;
            boolean snap = !id.equals(this.target) || generation != this.session || elapsed < 0 || elapsed > 250_000_000L;
            if (snap) {
                this.health = this.trail = targetHealth;
                this.absorption = targetAbsorption;
            } else {
                float dt = Math.min(0.05F, elapsed / 1_000_000_000F);
                this.health = approach(this.health, targetHealth, dt, 18F);
                this.trail = Math.max(this.health, approach(this.trail, targetHealth, dt, 4F));
                this.absorption = approach(this.absorption, targetAbsorption, dt, 14F);
                this.health = Math.min(this.health, 1F - this.absorption);
                this.trail = Math.min(this.trail, 1F - this.absorption);
            }
            this.target = id;
            this.session = generation;
            this.previous = now;
            return new Bars(this.health, this.trail, this.absorption);
        }

        private static float approach(final float from, final float to, final float dt, final float speed) {
            return from + (to - from) * (1F - (float) Math.exp(-dt * speed));
        }

        void clear() {
            this.target = null;
            this.previous = 0L;
            this.health = this.trail = this.absorption = 0F;
        }
    }
}
