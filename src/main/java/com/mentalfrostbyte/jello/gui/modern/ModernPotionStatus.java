package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList;
import com.mentalfrostbyte.jello.module.impl.gui.PotionStatus;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;

/**
 * SigmaModern's drawing of {@link PotionStatus}: the player's status effects, each with its SVG icon
 * ({@link ModernSvg#effectIcon}), on dark glass above the ArrayList. Drawn from {@link ModernHud#render} before the
 * list, on the side the list hangs from, and claims its height there so the list starts below it.
 *
 * <p>Effects slide in from the screen edge when they start and fade where they were when they end, as the ArrayList's
 * lines do. The duration bar runs from the longest this effect has had since it started (or was last topped up), not
 * from the potion's full length, which the client is never told.</p>
 */
final class ModernPotionStatus {
    private static final int MARGIN = 10;
    private static final int GAP = 3;
    private static final int ROW_H = 18;
    private static final int PAD = 5;
    private static final int ICON = 12;
    private static final float NAME_SCALE = 0.8F;
    private static final float TIME_SCALE = 0.74F;
    private static final int TILE_W = 28, TILE_H = 30, TILE_ICON = 14, TILES_PER_LINE = 6;
    private static final float TILE_TIME_SCALE = 0.6F;
    private static final int SURFACE_RGB = 0x101B26;
    private static final int EXPIRING = 0xFFFFC27A;
    private static final long GAP_NANOS = 250_000_000L;

    /** Every effect with a line on screen, including ones on their way out. Render-thread only. */
    private static final Map<Holder<MobEffect>, Row> ROWS = new IdentityHashMap<>();
    private static long lastDraw;
    private static float shownTop = -1F;

    private ModernPotionStatus() {}

    private static final class Row {
        float appear;
        /** Slot index, eased: fractional while the effects above it come and go. */
        float slot;
        boolean listed;
        // The last frame's look, kept so an effect that has ended can fade out as it was.
        String name = "";
        String time = "";
        String icon = "";
        int color;
        float ticks;
        boolean infinite;
        int longest;
    }

    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        PotionStatus status = Modules.enabled(PotionStatus.class);
        if (status == null || mc.player == null || mc.level == null) {
            ROWS.clear();
            lastDraw = 0L;
            return;
        }

        long now = System.nanoTime();
        float dt = lastDraw == 0L ? 0F : Math.min(0.05F, (now - lastDraw) / 1_000_000_000F);
        boolean snap = now - lastDraw > GAP_NANOS;
        lastDraw = now;

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float tickrate = mc.level.tickRateManager().tickrate();
        List<MobEffectInstance> listed = status.listed(mc.player.getActiveEffects(), ModernPotionStatus::entry);

        for (Row row : ROWS.values()) row.listed = false;
        for (int i = 0; i < listed.size(); i++) {
            MobEffectInstance instance = listed.get(i);
            Row row = ROWS.get(instance.getEffect());
            if (row == null) {
                row = new Row();
                row.slot = i;
                ROWS.put(instance.getEffect(), row);
            }
            update(row, instance, status, partial, tickrate);
            row.listed = true;
            row.slot = snap ? i : ModernStyle.smooth(row.slot, i, dt, 14F);
            row.appear = snap ? 1F : ModernStyle.smooth(row.appear, 1F, dt, 12F);
        }
        List<Row> leaving = new ArrayList<>();
        for (Iterator<Row> it = ROWS.values().iterator(); it.hasNext(); ) {
            Row row = it.next();
            if (row.listed) continue;
            row.appear = snap ? 0F : ModernStyle.smooth(row.appear, 0F, dt, 12F);
            if (row.appear < 0.02F) it.remove();
            else leaving.add(row);
        }
        if (ROWS.isEmpty()) return;

        // Above the ArrayList, on its side: the left under TabGUI and the keystrokes when the list hangs top-left,
        // otherwise the top-right corner vanilla's icons had. Below F3's text on that side while it's up.
        ModuleArrayList list = Modules.enabled(ModuleArrayList.class);
        boolean left = list != null && list.getPosition() == ModuleArrayList.Corner.TOP_LEFT;
        int target = left ? Math.max(ModernHud.leftStack(), ModernHud.debugBottom(true) + 6)
            : Math.max(ModernHud.rightStack(), ModernHud.debugBottom(false) + 6);
        shownTop = snap || shownTop < 0F ? target : ModernStyle.smooth(shownTop, target, dt, 14F);
        int top = Math.round(shownTop);

        boolean compact = status.getLayout() == PotionStatus.Layout.COMPACT;
        float height = 0F;
        if (compact) {
            for (Row row : leaving) height = Math.max(height, drawTile(g, status, row, top, left));
            for (MobEffectInstance instance : listed) height = Math.max(height, drawTile(g, status, ROWS.get(instance.getEffect()), top, left));
        } else {
            int width = listWidth(status);
            for (Row row : leaving) height = Math.max(height, drawLine(g, status, row, top, width, left));
            for (MobEffectInstance instance : listed) height = Math.max(height, drawLine(g, status, ROWS.get(instance.getEffect()), top, width, left));
        }
        ModernHud.claim(left, Math.round(top + height));
    }

    private static PotionStatus.Entry entry(MobEffectInstance instance) {
        MobEffect effect = instance.getEffect().value();
        return new PotionStatus.Entry(effect.getDisplayName().getString(), effect.getCategory(), instance.getDuration(),
            instance.isInfiniteDuration(), instance.isAmbient(), instance.showIcon());
    }

    private static void update(Row row, MobEffectInstance instance, PotionStatus status, float partial, float tickrate) {
        String level = PotionStatus.level(instance.getAmplifier());
        String name = instance.getEffect().value().getDisplayName().getString();
        row.name = status.showsLevel() && !level.isEmpty() ? name + " " + level : name;
        row.time = MobEffectUtil.formatDuration(instance, 1.0F, tickrate).getString();
        row.icon = ModernSvg.effectIcon(instance.getEffect());
        row.color = status.hasColoredIcons() ? iconColor(instance.getEffect().value().getColor()) : ModernStyle.TEXT;
        int ticks = instance.getDuration();
        // Topped up (another potion, a beacon pulse): the bar measures from the new length.
        if (ticks > row.ticks + 1F || row.longest == 0) row.longest = ticks;
        row.longest = Math.max(row.longest, ticks);
        row.infinite = instance.isInfiniteDuration();
        row.ticks = row.infinite ? ticks : Math.max(0F, ticks - partial);
    }

    /** One width for every line, so the column's edges line up: the widest name and time, plus the icon and padding. */
    private static int listWidth(PotionStatus status) {
        float widest = 0F;
        for (Row row : ROWS.values()) {
            float w = ModernTypography.width(ModernTypography.Face.TEXT, row.name, NAME_SCALE);
            if (status.showsTime()) w += 8F + ModernTypography.width(ModernTypography.Face.TEXT, row.time, TIME_SCALE);
            widest = Math.max(widest, w);
        }
        return (int)Math.ceil(PAD + ICON + 5 + widest + PAD + 1);
    }

    /** A LIST line; returns how far below {@code top} it reaches. */
    private static float drawLine(GuiGraphicsExtractor g, PotionStatus status, Row row, int top, int width, boolean left) {
        float slide = (1F - row.appear) * (width + MARGIN + 2);
        float x = left ? MARGIN - slide : g.guiWidth() - MARGIN - width + slide;
        float y = top + row.slot * (ROW_H + GAP);
        boolean expiring = !row.infinite && row.ticks <= PotionStatus.EXPIRING_TICKS;
        try (ModernStyle.AlphaScope ignored = ModernStyle.alphaScope(row.appear)) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(Math.round(x), Math.round(y));
                glass(g, status, width, ROW_H);
                float blink = status.blinksExpiring() ? PotionStatus.blink(row.ticks, row.infinite) : 1F;
                try (ModernStyle.AlphaScope ignoredToo = ModernStyle.alphaScope(blink)) {
                    ModernSvg.mask(g, row.icon, PAD, (ROW_H - ICON) / 2F, ICON, ICON, row.color);
                }
                boolean bar = status.hasDurationBar() && !row.infinite && row.longest > 0;
                float textY = (ROW_H - ModernFontRenderer.SIZE * NAME_SCALE) / 2F - (bar ? 1F : 0F);
                float textX = PAD + ICON + 5;
                ModernTypography.draw(g, row.name, textX, textY, NAME_SCALE, ModernStyle.TEXT, status.getBackground() <= 0F);
                if (status.showsTime()) {
                    float timeW = ModernTypography.width(ModernTypography.Face.TEXT, row.time, TIME_SCALE);
                    float timeY = textY + ModernFontRenderer.SIZE * (NAME_SCALE - TIME_SCALE) * 0.7F;
                    ModernTypography.draw(g, row.time, width - PAD - timeW, timeY, TIME_SCALE,
                        expiring ? EXPIRING : ModernStyle.MUTED, status.getBackground() <= 0F);
                }
                if (bar) {
                    int barX = (int)textX, barW = width - PAD - barX, barY = ROW_H - 4;
                    float left01 = Math.min(1F, row.ticks / row.longest);
                    ModernStyle.rounded(g, barX, barY, barW, 2, 1, 0x30FFFFFF);
                    int fill = Math.round(barW * left01);
                    if (fill > 0) ModernStyle.rounded(g, barX, barY, fill, 2, 1, row.color);
                }
            } finally {
                g.pose().popMatrix();
            }
        }
        return (row.slot + row.appear) * (ROW_H + GAP) - GAP * row.appear;
    }

    /** A COMPACT tile: the icon with the time under it, side by side from the screen edge in. */
    private static float drawTile(GuiGraphicsExtractor g, PotionStatus status, Row row, int top, boolean left) {
        int tileH = status.showsTime() ? TILE_H : TILE_W - 4;
        int line = (int)Math.floor(row.slot / TILES_PER_LINE);
        float column = row.slot - line * TILES_PER_LINE;
        float step = TILE_W + GAP;
        float slide = (1F - row.appear) * (TILE_W + MARGIN + 2);
        float x = left ? MARGIN + column * step - slide : g.guiWidth() - MARGIN - TILE_W - column * step + slide;
        float y = top + line * (tileH + GAP);
        boolean expiring = !row.infinite && row.ticks <= PotionStatus.EXPIRING_TICKS;
        try (ModernStyle.AlphaScope ignored = ModernStyle.alphaScope(row.appear)) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(Math.round(x), Math.round(y));
                glass(g, status, TILE_W, tileH);
                float blink = status.blinksExpiring() ? PotionStatus.blink(row.ticks, row.infinite) : 1F;
                try (ModernStyle.AlphaScope ignoredToo = ModernStyle.alphaScope(blink)) {
                    ModernSvg.mask(g, row.icon, (TILE_W - TILE_ICON) / 2F, 4F, TILE_ICON, TILE_ICON, row.color);
                }
                if (status.showsTime()) {
                    float timeW = ModernTypography.width(ModernTypography.Face.TEXT, row.time, TILE_TIME_SCALE);
                    ModernTypography.draw(g, row.time, (TILE_W - timeW) / 2F, tileH - 4F - ModernFontRenderer.SIZE * TILE_TIME_SCALE,
                        TILE_TIME_SCALE, expiring ? EXPIRING : ModernStyle.MUTED, status.getBackground() <= 0F);
                }
            } finally {
                g.pose().popMatrix();
            }
        }
        return (line + 1) * (tileH + GAP) - GAP;
    }

    private static void glass(GuiGraphicsExtractor g, PotionStatus status, int w, int h) {
        float solid = status.getBackground();
        if (solid <= 0F) return;
        try (ModernStyle.AlphaScope ignored = ModernStyle.alphaScope(Math.min(1F, solid * 1.4F))) {
            ModernStyle.darkGlass(g, 0, 0, w, h, 5, Math.round(solid * 0xCC) << 24 | SURFACE_RGB);
        }
    }

    /**
     * An effect's color made light enough to read on dark glass: mixed toward white until it is at least as bright as
     * {@link #MIN_LUMA}, so blindness's near-black and darkness's grey still show while bright colors keep their hue.
     */
    static int iconColor(int rgb) {
        float luma = luma(rgb);
        if (luma >= MIN_LUMA) return 0xFF000000 | rgb;
        return ModernStyle.mix(0xFF000000 | rgb, 0xFFFFFFFF, (MIN_LUMA - luma) / (1F - luma));
    }

    static final float MIN_LUMA = 0.62F;

    static float luma(int rgb) {
        return (0.2126F * (rgb >> 16 & 0xFF) + 0.7152F * (rgb >> 8 & 0xFF) + 0.0722F * (rgb & 0xFF)) / 255F;
    }
}
