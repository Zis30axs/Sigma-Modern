package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList.ColorMode;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList.Corner;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * SigmaModern's drawing of the {@link ModuleArrayList}: the switched-on modules, one line each, hanging from a corner
 * of the game screen. Drawn from {@link ModernHud#render} while that module is on; which modules, in what order and
 * in what style is the module's to say.
 *
 * <p>With {@code Animations} on, a module switched on slides in from the screen edge and one switched off slides back
 * out and fades, while the lines around it ease into their new places. After a gap in drawing (a screen was open, the
 * world was loading) the list snaps to where it should be instead of replaying what changed meanwhile.</p>
 */
final class ModernArrayList {
    private static final String LAYOUT_ID = "array-list";
    /** Distance from the screen edges, as the list always had. */
    private static final int MARGIN = 10;
    /** How far a line's background reaches past its text on either side. */
    private static final int PAD_X = 3;
    private static final int BAR_W = 2;
    private static final int BACKGROUND_RGB = 0x101B26;
    private static final long GAP_NANOS = 250_000_000L;

    /** Every module with a line on screen, including ones on their way out. Render-thread only. */
    private static final Map<Module, Row> ROWS = new IdentityHashMap<>();
    private static long lastDraw;
    // Where the top of the list is, eased toward where it should be.
    private static float listTop = -1F;
    private static float time;
    // How far a bottom-right list reached up, and a top-right one down, this frame; past the screen while there's none.
    private static int bottomRightTop = Integer.MAX_VALUE;
    private static int topRightBottom = Integer.MIN_VALUE;

    private ModernArrayList() {}

    private static final class Row {
        /** 0 off screen, 1 fully in. */
        float appear;
        /** Distance from the corner the list hangs from to this line's near edge. */
        float offset;
        boolean listed;
    }

    /** One frame's geometry is shared by the glow, glass and ink passes, including departing lines. */
    private record Line(String name, @Nullable String suffix, float width, float x, float y, int color, float alpha) {}

    static void render(GuiGraphicsExtractor g) {
        ModuleArrayList list = Modules.enabled(ModuleArrayList.class);
        bottomRightTop = Integer.MAX_VALUE;
        topRightBottom = Integer.MIN_VALUE;
        if (list == null) {
            ROWS.clear();
            lastDraw = 0L;
            return;
        }

        long now = System.nanoTime();
        float dt = lastDraw == 0L ? 0F : Math.min(0.05F, (now - lastDraw) / 1_000_000_000F);
        boolean editing = ModernHudEditor.isEditing();
        boolean custom = ModernHudEditor.hasPosition(LAYOUT_ID);
        boolean snap = editing || !list.isAnimated() || now - lastDraw > GAP_NANOS;
        lastDraw = now;
        time += dt;

        float scale = list.getFontSize() / ModernFontRenderer.SIZE;
        int rowH = Math.round(ModernFontRenderer.SIZE * scale) + list.getSpacing();
        List<Module> listed = list.listed(Client.getInstance().getModuleManager().all(),
            module -> ModernTypography.width(label(module.getName(), list.suffixOf(module))));

        for (Row row : ROWS.values()) row.listed = false;
        for (int i = 0; i < listed.size(); i++) {
            float target = i * rowH;
            Row row = ROWS.get(listed.get(i));
            if (row == null) {
                // A new line starts in its own slot and slides in sideways; the others make room around it.
                row = new Row();
                row.offset = target;
                ROWS.put(listed.get(i), row);
            }
            row.listed = true;
            row.offset = snap ? target : ModernStyle.smooth(row.offset, target, dt, 14F);
            row.appear = snap ? 1F : ModernStyle.smooth(row.appear, 1F, dt, 12F);
        }

        // Lines on their way out stay in their slot and are drawn first, so the lines closing the gap pass over them.
        List<Map.Entry<Module, Row>> leaving = new ArrayList<>();
        for (Iterator<Map.Entry<Module, Row>> it = ROWS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Module, Row> entry = it.next();
            Row row = entry.getValue();
            if (row.listed) continue;
            row.appear = snap ? 0F : ModernStyle.smooth(row.appear, 0F, dt, 12F);
            if (row.appear < 0.02F) it.remove();
            else leaving.add(entry);
        }

        // As the old ActiveMods did: top-right starts below F3's right column while that's up; top-left hangs under the
        // left stack (brand, TabGUI, keystrokes), or under F3's left column; top-right also below vanilla's effect icons
        // while those are drawn (the right stack). Eased, so the list slides rather than jumps.
        int target = switch (list.getPosition()) {
            case TOP_RIGHT -> Math.max(ModernHud.rightStack(), ModernHud.debugBottom(false) + 6);
            case TOP_LEFT -> Math.max(ModernHud.leftStack(), ModernHud.debugBottom(true) + 6);
            case BOTTOM_RIGHT -> MARGIN;
        };
        listTop = snap || listTop < 0F ? target : ModernStyle.smooth(listTop, target, dt, 14F);
        int top = Math.round(listTop);
        float reach = 0F;
        float widest = 0F;
        for (Map.Entry<Module, Row> entry : ROWS.entrySet()) {
            reach = Math.max(reach, entry.getValue().offset + rowH);
            widest = Math.max(widest, ModernTypography.width(ModernTypography.Face.TEXT,
                    label(entry.getKey().getName(), list.suffixOf(entry.getKey())), scale));
        }
        // Empty enabled lists can still be placed; this is a label, never an enabled preview module.
        String preview = ROWS.isEmpty() && editing ? ModernText.t("ArrayList · Preview", "模块列表 · 预览") : null;
        if (preview != null) {
            widest = ModernTypography.width(ModernTypography.Face.TEXT, preview, scale);
            reach = rowH;
        }
        if (reach <= 0F) return;
        Corner corner = list.getPosition();
        float accent = list.hasAccentBar() ? BAR_W + 1 : 0;
        float left = corner.isRight() ? g.guiWidth() - MARGIN - widest - PAD_X : MARGIN - PAD_X - accent;
        float y = corner.isBottom() ? g.guiHeight() - MARGIN - reach : top;
        var bounds = ModernHudEditor.place(g, LAYOUT_ID, ModernText.t("Module list", "模块列表"),
                left, y, (float)Math.ceil(widest) + PAD_X * 2 + accent + 1, reach);
        // A manually placed list no longer reserves either automatic right-side stack.
        if (!custom && !ROWS.isEmpty() && corner == Corner.BOTTOM_RIGHT) bottomRightTop = Math.round(bounds.y());
        if (!custom && !ROWS.isEmpty() && corner == Corner.TOP_RIGHT) topRightBottom = Math.round(bounds.y() + reach);
        List<Line> lines = new ArrayList<>(leaving.size() + listed.size() + (preview != null ? 1 : 0));
        for (Map.Entry<Module, Row> entry : leaving) lines.add(line(g, list, entry.getKey(), entry.getValue(), scale, rowH, top));
        for (Module module : listed) lines.add(line(g, list, module, ROWS.get(module), scale, rowH, top));
        if (preview != null) {
            float textX = corner.isRight() ? g.guiWidth() - MARGIN - widest : MARGIN;
            lines.add(new Line(preview, null, widest, textX, y, ModernStyle.MUTED, 1F));
        }
        g.pose().pushMatrix();
        try {
            g.pose().translate(bounds.dx(), bounds.dy());
            // Finish every halo before any glass or text: neighbouring rows must not glow over each other's ink.
            if (list.hasBloom() && list.getBloomStrength() > 0F) {
                for (Line line : lines) drawGlow(g, list, line, rowH);
                g.nextStratum();
            }
            // Departing lines precede active ones, preserving their occlusion while the remaining rows close a gap.
            for (Line line : lines) {
                drawSurface(g, list, line, rowH);
                drawInk(g, list, line, scale, rowH);
            }
        } finally {
            g.pose().popMatrix();
        }
    }

    /**
     * How far up a list hanging from the bottom-right corner reached this frame, or the screen's height while there's
     * none: PotionStatus's effects stack up from above it. {@link ModernHud#render} draws the list first.
     */
    static int bottomRightTop(GuiGraphicsExtractor g) {
        return Math.min(bottomRightTop, g.guiHeight());
    }

    /** How far down a top-right list reached this frame, or 0 while there's none: PotionStatus's effects stop below it. */
    static int topRightBottom() {
        return Math.max(topRightBottom, 0);
    }

    private static Line line(GuiGraphicsExtractor g, ModuleArrayList list, Module module, Row row, float scale, int rowH, int top) {
        String name = module.getName();
        String suffix = list.suffixOf(module);
        float width = ModernTypography.width(ModernTypography.Face.TEXT, label(name, suffix), scale);
        Corner corner = list.getPosition();
        // Out of the way entirely at appear 0: past the screen edge, accent bar and all.
        float slide = (1F - row.appear) * (width + MARGIN + PAD_X + BAR_W + 2);
        float x = corner.isRight() ? g.guiWidth() - MARGIN - width + slide : MARGIN - slide;
        float y = corner.isBottom() ? g.guiHeight() - MARGIN - rowH - row.offset : top + row.offset;
        int color = lineColor(list.getColorMode(), list.getColor(), row.offset / rowH, time);
        return new Line(name, suffix, width, x, y, color, row.appear);
    }

    private static void drawGlow(GuiGraphicsExtractor g, ModuleArrayList list, Line line, int rowH) {
        try (var ignored = ModernStyle.alphaScope(line.alpha)) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(line.x, line.y);
                int boxW = (int)Math.ceil(line.width) + PAD_X * 2;
                ModernBloom.rounded(g, -PAD_X, 0, boxW, rowH, 3, line.color, list.getBloomStrength());
            } finally {
                g.pose().popMatrix();
            }
        }
    }

    private static void drawSurface(GuiGraphicsExtractor g, ModuleArrayList list, Line line, int rowH) {
        try (var ignored = ModernStyle.alphaScope(line.alpha)) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(line.x, line.y);
                int boxW = (int)Math.ceil(line.width) + PAD_X * 2;
                if (list.hasBlur() && list.getBlurStrength() > 0F) {
                    g.sigma$blurRounded(-PAD_X, 0, boxW, rowH, 3, list.getBlurStrength());
                }
                if (list.getBackground() > 0F) {
                    int alpha = Math.round(list.getBackground() * 0xCC);
                    ModernStyle.rounded(g, -PAD_X, 0, boxW, rowH, 3, alpha << 24 | BACKGROUND_RGB);
                }
            } finally {
                g.pose().popMatrix();
            }
        }
    }

    private static void drawInk(GuiGraphicsExtractor g, ModuleArrayList list, Line line, float scale, int rowH) {
        try (var ignored = ModernStyle.alphaScope(line.alpha)) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(line.x, line.y);
                int boxW = (int)Math.ceil(line.width) + PAD_X * 2;
                if (list.hasAccentBar()) {
                    int barX = list.getPosition().isRight() ? boxW - PAD_X + 1 : -PAD_X - 1 - BAR_W;
                    ModernStyle.rounded(g, barX, 0, BAR_W, rowH, 1, line.color);
                }
                float textY = list.getSpacing() / 2F;
                ModernTypography.draw(g, line.name, 0F, textY, scale, line.color, list.hasTextShadow());
                if (line.suffix != null) {
                    // Right-aligned to the whole label's measured end, so it never overlaps the name.
                    float suffixX = line.width - ModernTypography.width(ModernTypography.Face.TEXT, line.suffix, scale);
                    ModernTypography.draw(g, line.suffix, suffixX, textY, scale, ModernStyle.MUTED, list.hasTextShadow());
                }
            } finally {
                g.pose().popMatrix();
            }
        }
    }

    static String label(String name, @Nullable String suffix) {
        return suffix == null ? name : name + " " + suffix;
    }

    /**
     * A line's color. {@code position} is the line's place in the list (fractional while it moves), so the wave and
     * the rainbow run smoothly down the list and follow a line as it moves.
     */
    static int lineColor(ColorMode mode, int base, float position, float seconds) {
        return switch (mode) {
            case STATIC -> base;
            case WAVE -> {
                float brightness = 0.775F + 0.225F * (float)Math.cos(seconds * 2.5F - position * 0.45F);
                int r = Math.round((base >> 16 & 0xFF) * brightness);
                int gr = Math.round((base >> 8 & 0xFF) * brightness);
                int b = Math.round((base & 0xFF) * brightness);
                yield base & 0xFF000000 | r << 16 | gr << 8 | b;
            }
            case RAINBOW -> {
                float hue = seconds * 0.12F + position * 0.04F;
                yield Mth.hsvToArgb(hue - (float)Math.floor(hue), 0.45F, 1F, 0xFF);
            }
        };
    }
}
