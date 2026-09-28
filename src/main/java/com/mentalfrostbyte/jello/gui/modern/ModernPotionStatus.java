package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList;
import com.mentalfrostbyte.jello.module.impl.gui.PotionStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;

/**
 * SigmaModern's drawing of {@link PotionStatus}: the player's status effects, each with its SVG icon
 * ({@link ModernSvg#effectIcon}), a dark-glass card each. Drawn from {@link ModernHud#render} before the ArrayList,
 * down the top-right corner (the left side under the TabGUI when the list hangs top-left), and claims its height there
 * so a list in the same corner starts below it.
 *
 * <p>Effects slide in from the screen edge when they start and fade where they were when they end, as the ArrayList's
 * lines do. The duration bar runs from the longest this effect has had since it started (or was last topped up), not
 * from the potion's full length, which the client is never told.</p>
 */
final class ModernPotionStatus {
    private static final int MARGIN = 10;
    private static final int CARD_MIN_W = 128, RADIUS = 7, PAD = 6;
    /** Space kept between the cards and a list hanging from the bottom-right corner. */
    private static final int LIST_CLEARANCE = 6;

    /**
     * A card's measurements: the icon on a tile in the effect's colour, then the name and the time on one line and the
     * bar under them. {@link #FULL} normally; {@link #SLIM} when that many full cards would run into a list in the
     * bottom-right corner.
     */
    record Size(int height, int gap, int tile, int icon, float nameScale, float timeScale, float textTop, int barH, int barBottom) {
        static final Size FULL = new Size(30, 4, 20, 14, 0.82F, 0.74F, 6F, 3, 6);
        static final Size SLIM = new Size(20, 3, 14, 11, 0.76F, 0.68F, 3F, 2, 3);

        int textX() {
            return PAD + this.tile + (this == SLIM ? 5 : 7);
        }

        /** How tall {@code count} cards stand, gaps between them included. */
        int stack(int count) {
            return count <= 0 ? 0 : count * (this.height + this.gap) - this.gap;
        }

        /**
         * Full cards if {@code count} of them fit in {@code room}, else slim ones (which may still not fit). Once slim,
         * full ones need {@link #SETTLE} to spare, so a list easing past the edge doesn't flip the cards back and forth.
         */
        static Size fitting(int count, int room, Size now) {
            return FULL.stack(count) <= room - (now == SLIM ? SETTLE : 0) ? FULL : SLIM;
        }

        static final int SETTLE = 8;

        /** How many of these cards fit in {@code room}. */
        int capacity(int room) {
            return room < this.height ? 0 : (room + this.gap) / (this.height + this.gap);
        }
    }
    private static final int SURFACE_RGB = 0x101B26;
    private static final int EXPIRING = 0xFFFFC27A;
    private static final long GAP_NANOS = 250_000_000L;

    /** Every effect with a line on screen, including ones on their way out. Render-thread only. */
    private static final Map<Holder<MobEffect>, Row> ROWS = new IdentityHashMap<>();
    private static long lastDraw;
    private static float shownTop = -1F;
    private static Size shownSize = Size.FULL;

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
        List<MobEffectInstance> active = status.listed(mc.player.getActiveEffects(), ModernPotionStatus::entry);

        // Down the top-right corner, where vanilla's icons were; down the left under the TabGUI and the keystrokes when
        // the ArrayList hangs top-left. Below F3's text on that side while it's up.
        ModuleArrayList list = Modules.enabled(ModuleArrayList.class);
        boolean left = list != null && list.getPosition() == ModuleArrayList.Corner.TOP_LEFT;
        int target = left ? Math.max(ModernHud.leftStack(), ModernHud.debugBottom(true) + 6)
            : Math.max(ModernHud.rightStack(), ModernHud.debugBottom(false) + 6);
        shownTop = snap || shownTop < 0F ? target : ModernStyle.smooth(shownTop, target, dt, 14F);
        int top = Math.round(shownTop);

        // Down to a list in the bottom-right corner, if one is there: slimmer cards when full ones would reach it, and
        // when even those don't fit, the effects with the most time left fold into a "+N more" card.
        int room = (left ? g.guiHeight() : ModernArrayList.bottomRightTop(g) - LIST_CLEARANCE) - top;
        Size size = shownSize = Size.fitting(active.size(), room, shownSize);
        List<MobEffectInstance> listed = active;
        int fit = size.capacity(room);
        if (active.size() > fit) listed = keepSoonest(active, Math.max(1, fit - 1), ModernPotionStatus::entry);
        int folded = active.size() - listed.size();

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

        float height = 0F;
        int width = cardWidth(status, size);
        for (Row row : leaving) height = Math.max(height, drawCard(g, status, size, row, top, width, left));
        for (MobEffectInstance instance : listed) height = Math.max(height, drawCard(g, status, size, ROWS.get(instance.getEffect()), top, width, left));
        if (folded > 0) height = Math.max(height, drawFolded(g, status, size, folded, listed.size(), top, width, left));
        ModernHud.claim(left, Math.round(top + height));
    }

    /**
     * The {@code keep} effects of {@code sorted} with the least time left (endless ones last), still in {@code sorted}'s
     * order: what stays on screen when there isn't room for every card. The rest have longest to go, so they need
     * watching least.
     */
    static <T> List<T> keepSoonest(List<T> sorted, int keep, Function<T, PotionStatus.Entry> entry) {
        if (sorted.size() <= keep) return sorted;
        Set<T> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        sorted.stream()
            .sorted(Comparator.comparing(entry, Comparator.comparing(PotionStatus.Entry::infinite).thenComparingInt(PotionStatus.Entry::ticks)))
            .limit(Math.max(0, keep))
            .forEach(kept::add);
        return sorted.stream().filter(kept::contains).toList();
    }

    /** The card standing in for {@code count} effects there was no room for, in slot {@code slot}. */
    private static float drawFolded(GuiGraphicsExtractor g, PotionStatus status, Size size, int count, int slot, int top, int width, boolean left) {
        float x = left ? MARGIN : g.guiWidth() - MARGIN - width;
        float y = top + slot * (size.height() + size.gap());
        g.pose().pushMatrix();
        try {
            g.pose().translate(Math.round(x), Math.round(y));
            glass(g, status, width, size.height());
            int tileY = (size.height() - size.tile()) / 2;
            ModernStyle.rounded(g, PAD, tileY, size.tile(), size.tile(), size.tile() / 4, 0x1FFFFFFF);
            float inset = (size.tile() - size.icon()) / 2F;
            ModernSvg.mask(g, ModernSvg.effectIcon("generic"), PAD + inset, tileY + inset, size.icon(), size.icon(), ModernStyle.MUTED);
            float textY = (size.height() - ModernFontRenderer.SIZE * size.nameScale()) / 2F;
            ModernTypography.draw(g, "+" + count + " more", size.textX(), textY, size.nameScale(), ModernStyle.MUTED, status.getBackground() <= 0F);
        } finally {
            g.pose().popMatrix();
        }
        return slot * (size.height() + size.gap()) + size.height();
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

    /** One width for every card, so the stack's edges line up: the widest name and time, at least {@link #CARD_MIN_W}. */
    private static int cardWidth(PotionStatus status, Size size) {
        float widest = 0F;
        for (Row row : ROWS.values()) {
            float w = ModernTypography.width(ModernTypography.Face.TEXT, row.name, size.nameScale());
            if (status.showsTime()) w += 10F + ModernTypography.width(ModernTypography.Face.TEXT, row.time, size.timeScale());
            widest = Math.max(widest, w);
        }
        return Math.max(CARD_MIN_W, (int)Math.ceil(size.textX() + widest + PAD));
    }

    /**
     * One effect's card: its icon on a rounded tile tinted with the effect's colour, the name and level with the time
     * at the far end, and under them the bar. Returns how far below {@code top} it reaches.
     */
    private static float drawCard(GuiGraphicsExtractor g, PotionStatus status, Size size, Row row, int top, int width, boolean left) {
        float slide = (1F - row.appear) * (width + MARGIN + 4);
        float x = left ? MARGIN - slide : g.guiWidth() - MARGIN - width + slide;
        float y = top + row.slot * (size.height() + size.gap());
        boolean expiring = !row.infinite && row.ticks <= PotionStatus.EXPIRING_TICKS;
        boolean shadow = status.getBackground() <= 0F;
        try (ModernStyle.AlphaScope ignored = ModernStyle.alphaScope(row.appear)) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(Math.round(x), Math.round(y));
                glass(g, status, width, size.height());

                float blink = status.blinksExpiring() ? PotionStatus.blink(row.ticks, row.infinite) : 1F;
                try (ModernStyle.AlphaScope ignoredToo = ModernStyle.alphaScope(blink)) {
                    int tileY = (size.height() - size.tile()) / 2;
                    ModernStyle.rounded(g, PAD, tileY, size.tile(), size.tile(), size.tile() / 4, 0x33000000 | row.color & 0xFFFFFF);
                    float inset = (size.tile() - size.icon()) / 2F;
                    ModernSvg.mask(g, row.icon, PAD + inset, tileY + inset, size.icon(), size.icon(), row.color);
                }

                boolean bar = status.hasDurationBar();
                int textX = size.textX();
                float nameH = ModernFontRenderer.SIZE * size.nameScale();
                float textY = bar ? size.textTop() : (size.height() - nameH) / 2F;
                ModernTypography.draw(g, row.name, textX, textY, size.nameScale(), ModernStyle.TEXT, shadow);
                if (status.showsTime()) {
                    float timeW = ModernTypography.width(ModernTypography.Face.TEXT, row.time, size.timeScale());
                    // On the name's baseline.
                    float timeY = textY + ModernFontRenderer.SIZE * (size.nameScale() - size.timeScale()) * 0.75F;
                    ModernTypography.draw(g, row.time, width - PAD - timeW, timeY, size.timeScale(), expiring ? EXPIRING : ModernStyle.MUTED, shadow);
                }
                if (bar) {
                    int barW = width - PAD - textX, barY = size.height() - size.barBottom() - size.barH();
                    ModernStyle.rounded(g, textX, barY, barW, size.barH(), 1, 0x2EFFFFFF);
                    if (row.infinite) {
                        // Endless: a full bar, dimmed, as nothing runs down.
                        ModernStyle.rounded(g, textX, barY, barW, size.barH(), 1, 0x73000000 | row.color & 0xFFFFFF);
                    } else if (row.longest > 0) {
                        int fill = Math.round(barW * Math.min(1F, row.ticks / row.longest));
                        if (fill > 0) ModernStyle.rounded(g, textX, barY, Math.max(fill, 2), size.barH(), 1, row.color);
                    }
                }
            } finally {
                g.pose().popMatrix();
            }
        }
        return (row.slot + row.appear) * (size.height() + size.gap()) - size.gap() * row.appear;
    }

    private static void glass(GuiGraphicsExtractor g, PotionStatus status, int w, int h) {
        float solid = status.getBackground();
        if (solid <= 0F) return;
        try (ModernStyle.AlphaScope ignored = ModernStyle.alphaScope(Math.min(1F, solid * 1.4F))) {
            ModernStyle.darkGlass(g, 0, 0, w, h, RADIUS, Math.round(solid * 0xCC) << 24 | SURFACE_RGB);
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
