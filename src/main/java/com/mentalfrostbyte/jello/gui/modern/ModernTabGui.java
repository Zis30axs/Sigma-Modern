package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Keybind;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.TabGui;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * SigmaModern's drawing of the {@link TabGui}: the categories on a dark glass panel in the top-left corner, and the
 * open category's modules on a second panel beside the highlighted row, each module with a status dot that is lit
 * while it is on and, when it has one, its key. The highlight is the hotbar's selected-slot glow. Drawn from
 * {@link ModernHud#render}; what the menu offers and where its selection is are the module's.
 *
 * <p>Outside HUD editing it steps aside while the F3 debug text, which starts in the same corner, is up - and since keys only move a menu
 * that is being drawn, the arrows are left alone meanwhile.</p>
 */
final class ModernTabGui {
    private static final String LAYOUT_ID = "tab-gui";
    /** Distance from the screen edges, the same as the ArrayList's. */
    static final int MARGIN = 10;
    static final int ROW_H = 15, PAD_Y = 3;
    private static final int PAD_X = 7, GAP = 4, RADIUS = 6, DOT = 4;
    private static final int SURFACE_RGB = 0x1C2A38;
    private static final int HIGHLIGHT = 0x5CB6E9FF;
    private static final long GAP_NANOS = 250_000_000L;

    private static long lastDraw;
    // Highlight offsets within their panels, and how far the module panel is open; -1 means "don't glide".
    private static float categoryY = -1F, moduleY = -1F, openness;
    // The category whose modules the panel shows, kept while it closes so it fades out with its own rows.
    private static @Nullable ModuleCategory shownCategory;

    private ModernTabGui() {}

    /** A panel's height for {@code rows} rows. */
    static int height(int rows) {
        return rows * ROW_H + PAD_Y * 2;
    }

    private static boolean visible() {
        return ModernHudEditor.isEditing() || !ModernHud.debugShowing();
    }

    static void render(GuiGraphicsExtractor g) {
        TabGui tab = Modules.enabled(TabGui.class);
        List<ModuleCategory> categories = tab == null ? List.of() : tab.categories();
        if (tab == null || !visible() || categories.isEmpty()) {
            lastDraw = 0L;
            return;
        }
        tab.markShown();

        long now = System.nanoTime();
        float dt = lastDraw == 0L ? 0F : Math.min(0.05F, (now - lastDraw) / 1_000_000_000F);
        boolean snap = ModernHudEditor.isEditing() || !tab.isAnimated() || now - lastDraw > GAP_NANOS;
        lastDraw = now;
        boolean shadow = tab.getBackground() < 0.35F;

        int selected = Math.floorMod(tab.selectedCategory(), categories.size());
        int catW = 0;
        for (ModuleCategory category : categories) catW = Math.max(catW, ModernTypography.width(ModernText.category(category)));
        catW += PAD_X * 2 + DOT + 8;
        openness = snap ? (tab.isOpen() ? 1F : 0F) : ModernStyle.smooth(openness, tab.isOpen() ? 1F : 0F, dt, 16F);
        if (tab.isOpen()) shownCategory = categories.get(selected);
        List<Module> modules = openness < 0.01F || shownCategory == null ? List.of() : tab.modules(shownCategory);
        int moduleTop = shownCategory == null ? 0 : Math.max(0, categories.indexOf(shownCategory)) * ROW_H;
        int moduleW = modules.isEmpty() ? 0 : moduleWidth(tab, modules);
        float width = modules.isEmpty() ? catW : catW + GAP + moduleW + (openness - 1F) * 6F;
        int height = Math.max(height(categories.size()), modules.isEmpty() ? 0 : moduleTop + height(modules.size()));

        // Only the default placement reserves space in the automatic left-hand stack.
        int x = MARGIN, y = ModernHud.leftStack();
        if (!ModernHudEditor.hasPosition(LAYOUT_ID)) ModernHud.stack(height(categories.size()));
        var bounds = ModernHudEditor.place(g, LAYOUT_ID, "TabGUI", x, y, width, height);
        g.pose().pushMatrix();
        try {
            g.pose().translate(bounds.dx(), bounds.dy());
            panel(g, tab, x, y, catW, height(categories.size()));
            float targetY = PAD_Y + selected * ROW_H;
            categoryY = snap || categoryY < 0F ? targetY : ModernStyle.smooth(categoryY, targetY, dt, 18F);
            // Dimmed while the modules have the keys, so it's clear which list Up and Down move.
            highlight(g, x + 2, y + categoryY, catW - 4, tab.isOpen() ? 0.4F : 1F);
            for (int i = 0; i < categories.size(); i++) {
                ModuleCategory category = categories.get(i);
                int rowY = y + PAD_Y + i * ROW_H;
                ModernTypography.draw(g, ModernText.category(category), x + PAD_X, rowY + 3, i == selected ? 0xFFFFFFFF : ModernStyle.TEXT, shadow);
                // A lit dot where something in the category is on.
                if (tab.modules(category).stream().anyMatch(Module::isEnabled)) {
                    ModernStyle.statusDot(g, x + catW - PAD_X - DOT, rowY + (ROW_H - DOT) / 2, DOT, ModernStyle.GLOW, true);
                }
            }
            if (modules.isEmpty()) moduleY = -1F;
            else drawModules(g, tab, modules, x + catW + GAP, y + moduleTop, moduleW, snap, dt, shadow);
        } finally {
            g.pose().popMatrix();
        }
    }

    private static int moduleWidth(TabGui tab, List<Module> modules) {
        int w = 0;
        for (Module module : modules) {
            String key = keyName(tab, module);
            int keyW = key == null ? 0 : 10 + Math.round(ModernTypography.width(ModernTypography.Face.TEXT, key, 0.8F));
            w = Math.max(w, DOT + 6 + ModernTypography.width(module.getName()) + keyW);
        }
        return w + PAD_X * 2;
    }

    private static void drawModules(GuiGraphicsExtractor g, TabGui tab, List<Module> modules, int x, int y, int w, boolean snap,
                                    float dt, boolean shadow) {
        int selected = Math.floorMod(tab.selectedModule(), modules.size());
        try (var fade = ModernStyle.alphaScope(openness)) {
            g.pose().pushMatrix();
            try {
                // Slides out from under the category panel as it opens.
                g.pose().translate((openness - 1F) * 6F, 0F);
                panel(g, tab, x, y, w, height(modules.size()));
                float targetY = PAD_Y + selected * ROW_H;
                moduleY = snap || moduleY < 0F ? targetY : ModernStyle.smooth(moduleY, targetY, dt, 18F);
                highlight(g, x + 2, y + moduleY, w - 4, 1F);
                for (int i = 0; i < modules.size(); i++) {
                    Module module = modules.get(i);
                    int rowY = y + PAD_Y + i * ROW_H;
                    boolean on = module.isEnabled(), current = i == selected;
                    ModernStyle.statusDot(g, x + PAD_X, rowY + (ROW_H - DOT) / 2, DOT, ModernStyle.GLOW, on);
                    int color = on ? (current ? 0xFFFFFFFF : ModernStyle.TEXT) : (current ? 0xFFD6E6F0 : ModernStyle.MUTED);
                    ModernTypography.draw(g, module.getName(), x + PAD_X + DOT + 6, rowY + 3, color, shadow);
                    String key = keyName(tab, module);
                    if (key != null) {
                        float keyW = ModernTypography.width(ModernTypography.Face.TEXT, key, 0.8F);
                        ModernTypography.draw(g, key, x + w - PAD_X - keyW, rowY + 4.5F, 0.8F, ModernStyle.MUTED, shadow);
                    }
                }
            } finally {
                g.pose().popMatrix();
            }
        }
    }

    /** The module's key as the game names it ("R", "Left Shift"), or null when it has none or keys aren't shown. */
    private static @Nullable String keyName(TabGui tab, Module module) {
        Keybind keybind = module.getKeybind();
        return tab.showsKeybinds() && keybind.isBound() ? keybind.key().getDisplayName().getString() : null;
    }

    private static void panel(GuiGraphicsExtractor g, TabGui tab, int x, int y, int w, int h) {
        float background = tab.getBackground();
        if (background > 0F) ModernStyle.darkGlass(g, x, y, w, h, RADIUS, Math.round(background * 0xB4) << 24 | SURFACE_RGB);
    }

    private static void highlight(GuiGraphicsExtractor g, int x, float y, int w, float strength) {
        int top = Math.round(y);
        ModernStyle.halo(g, x, top, w, ROW_H, 4, ModernStyle.GLOW, 0.3F * strength);
        ModernStyle.rounded(g, x, top, w, ROW_H, 4, ModernTypography.fade(HIGHLIGHT, strength));
    }
}
