package com.mentalfrostbyte.jello.gui.legacy.hud;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.impl.gui.TabGui;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jello's TabGUI: a 150-pixel panel of the categories at the left edge under the watermark, showing five of them at a
 * time and scrolling to keep the selection in view, with the highlighted category's modules on a second panel beside
 * it. Each row is 30 pixels of Helvetica Neue Light; the highlighted row slides 14 pixels to the right and its band
 * glides to the next row, and a module that is on is set in the Medium weight.
 *
 * <p>The old panels were frosted glass over the blurred scene, which the 26.2 GUI cannot cut out for a rectangle, so
 * they are a translucent dark plate with the same soft edge; the highlight keeps its inset look from the shadow
 * strips along its top and bottom.</p>
 */
final class JelloTabGui {
    /** Jello's categories, in its order: no Exploit and no Interface. */
    static final TabGui.Layout LAYOUT = new TabGui.Layout(List.of(
            ModuleCategory.MOVEMENT, ModuleCategory.PLAYER, ModuleCategory.COMBAT, ModuleCategory.ITEM,
            ModuleCategory.RENDER, ModuleCategory.WORLD, ModuleCategory.MISC), false);

    private static final int X = 10;
    private static final int WIDTH = 150;
    private static final int ROW = 30;
    private static final int PAD = 4;
    private static final int CATEGORY_ROWS = 5;
    private static final int MODULE_X = 170;
    private static final int MODULE_WIDTH = 170;
    /** How far the highlighted row's text slides to the right. */
    private static final float SLIDE = 14.0F;
    private static final float SLIDE_SPEED = 90.0F;
    private static final int TEXT = 0xFFFEFEFE;
    private static final int PLATE = 0x59101820;
    private static final int BAND = 0x24FFFFFF;

    private static final float[] CATEGORY_SLIDE = new float[ModuleCategory.values().length];
    private static final Map<Module, Float> MODULE_SLIDE = new IdentityHashMap<>();
    private static boolean placed;
    private static float categoryScroll;
    private static float categoryBand;
    private static float moduleScroll;
    private static float moduleBand;
    private static float openness;

    private JelloTabGui() {}

    /** Called for a frame that does not draw the panel: the next one places the band where it belongs rather than gliding. */
    static void hidden() {
        placed = false;
    }

    /** Draws the menu with its top at {@code top}; returns where the category panel ends. */
    static int render(final LegacyCanvas c, final TabGui tab, final int top, final float dt) {
        tab.markShown(LAYOUT);
        List<ModuleCategory> categories = tab.categories();
        if (categories.isEmpty()) {
            return top;
        }

        int selected = Math.floorMod(tab.selectedCategory(), categories.size());
        int viewRows = Math.min(categories.size(), CATEGORY_ROWS);
        int viewHeight = viewRows * ROW + PAD;

        float scrollTarget = Math.max(selected * ROW - (CATEGORY_ROWS - 1) * ROW, 0);
        float bandTarget = selected * ROW;
        if (!placed) {
            categoryScroll = scrollTarget;
            categoryBand = bandTarget;
            moduleScroll = 0.0F;
            moduleBand = 0.0F;
            openness = tab.isOpen() ? 1.0F : 0.0F;
            placed = true;
        }
        categoryScroll = LegacyHud.smooth(categoryScroll, scrollTarget, dt, 9.0F);
        categoryBand = LegacyHud.smooth(categoryBand, bandTarget, dt, 9.0F);
        openness = LegacyHud.approach(openness, tab.isOpen() ? 1.0F : 0.0F, dt * 8.0F);

        panel(c, X, top, WIDTH, viewHeight, 1.0F);
        c.scissor(X, top, X + WIDTH, top + viewHeight);
        try {
            int base = top - Math.round(categoryScroll);
            band(c, X, base + Math.round(categoryBand), WIDTH, 1.0F);
            Face light = Face.JELLO_LIGHT;
            float textHeight = c.textHeight(light, 20.0F);
            for (int i = 0; i < categories.size(); i++) {
                CATEGORY_SLIDE[i] = LegacyHud.approach(CATEGORY_SLIDE[i], i == selected ? SLIDE : 0.0F, SLIDE_SPEED * dt);
                float y = base + ROW / 2.0F - textHeight / 2.0F + 2.0F + i * ROW;
                c.text(light, 20.0F, categories.get(i).toString(), X + 11 + CATEGORY_SLIDE[i], y, TEXT);
            }
        } finally {
            c.unscissor();
        }

        if (openness > 0.0F) {
            modules(c, tab, categories.get(selected), top, dt);
        }

        return top + viewHeight;
    }

    private static void modules(final LegacyCanvas c, final TabGui tab, final ModuleCategory category, final int top, final float dt) {
        List<Module> modules = tab.modules(category);
        if (modules.isEmpty()) {
            return;
        }

        int selected = Math.floorMod(tab.selectedModule(), modules.size());
        // The old panel was as tall as its list; a long one is kept to the screen and scrolled like the categories.
        int fits = Math.max(CATEGORY_ROWS, (c.height() - top - 20) / ROW);
        int viewRows = Math.min(modules.size(), fits);
        int viewHeight = viewRows * ROW + PAD;
        moduleScroll = LegacyHud.smooth(moduleScroll, Math.max(selected * ROW - (viewRows - 1) * ROW, 0), dt, 9.0F);
        moduleBand = LegacyHud.smooth(moduleBand, selected * ROW, dt, 9.0F);

        // The module panel opens with a short fade; the colours carry it.
        float alpha = openness;
        panel(c, MODULE_X, top, MODULE_WIDTH, viewHeight, alpha);
        c.scissor(MODULE_X, top, MODULE_X + MODULE_WIDTH, top + viewHeight);
        try {
            int base = top - Math.round(moduleScroll);
            band(c, MODULE_X, base + Math.round(moduleBand), MODULE_WIDTH, alpha);
            for (int i = 0; i < modules.size(); i++) {
                Module module = modules.get(i);
                float slide = LegacyHud.approach(MODULE_SLIDE.getOrDefault(module, 0.0F), i == selected ? SLIDE : 0.0F, SLIDE_SPEED * dt);
                MODULE_SLIDE.put(module, slide);
                // On is the Medium weight, off the Light one - the only difference the old list made.
                Face face = module.isEnabled() ? Face.JELLO_MEDIUM : Face.JELLO_LIGHT;
                float y = base + ROW / 2.0F - c.textHeight(face, 20.0F) / 2.0F + (module.isEnabled() ? 3.0F : 2.0F) + i * ROW;
                c.text(face, 20.0F, module.getName(), MODULE_X + 11 + slide, y, LegacyCanvas.fade(TEXT, alpha));
            }
        } finally {
            c.unscissor();
        }
    }

    /** A panel: the dark plate and the soft edge the old {@code drawRoundedRect} cast round it. */
    private static void panel(final LegacyCanvas c, final int x, final int y, final int w, final int h, final float alpha) {
        c.outerGlow(x, y, w, h, 8.0F, 0.7F * alpha);
        c.fill(x, y, x + w, y + h, LegacyCanvas.fade(PLATE, alpha));
    }

    /** The selection band: a faint light plate, 34 pixels tall, with the shadow strips that make it look inset. */
    private static void band(final LegacyCanvas c, final int x, final int y, final int w, final float alpha) {
        int glow = LegacyCanvas.alpha(0xFFFEFEFE, 0.3F * alpha);
        c.fill(x, y, x + w, y + ROW + PAD, LegacyCanvas.fade(BAND, alpha));
        c.image(LegacyTexture.SHADOW_TOP, x, y + ROW - 10, w, 14, glow);
        c.image(LegacyTexture.SHADOW_BOTTOM, x, y, w, 14, glow);
    }
}
