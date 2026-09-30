package com.mentalfrostbyte.jello.gui.legacy.hud;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.impl.gui.TabGui;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.ColorSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.util.math.Easing;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Classic's TabGUI: dark panels of 25-pixel rows at the top left under the watermark, opening to the right one level
 * at a time - categories, then a category's modules, then a module's settings. The row you are on is a band running
 * from deep navy to bright blue; a module that is off is grey.
 *
 * <p>Down at the settings, Right starts editing the setting (a pointer opens between the panel and a box that
 * describes it, and Up and Down change the value); Left stops. A panel closing folds back to its left edge, and the
 * box beside the deepest panel grows out again after every key press, as it did in the old client. The whole menu
 * dims to half while it has not been touched for four seconds.</p>
 */
final class ClassicTabGui {
    /** Classic's categories, in its order: no Item, Exploit or Interface. The settings level is what sets it apart. */
    static final TabGui.Layout LAYOUT = new TabGui.Layout(List.of(
            ModuleCategory.COMBAT, ModuleCategory.PLAYER, ModuleCategory.MOVEMENT, ModuleCategory.RENDER,
            ModuleCategory.WORLD, ModuleCategory.MISC), true);

    /** The old palette's near-black and near-white ({@code DEEP_TEAL} and {@code LIGHT_GREYISH_BLUE}). */
    static final int PANEL = 0xFF010101;
    static final int TEXT = 0xFFFEFEFE;
    private static final int GREY = 0xFF999999;
    private static final int BAND_LEFT = 0xFF0F3360;
    private static final int BAND_RIGHT = 0xFF118FC0;

    private static final Face FACE = Face.CLASSIC_BOLD;
    private static final float SIZE = 16.0F;
    private static final int X = 4;
    private static final int TOP = 30;
    private static final int ROW = 25;
    private static final int PAD = 4;
    private static final int GAP = 5;
    private static final int MIN_WIDTH = 106;
    /** How long the menu stays lit after a key press, and after it is switched on. */
    private static final long LIT_AFTER_PRESS = 4_000_000_000L;
    private static final long LIT_AFTER_ENABLE = 2_000_000_000L;

    // Whether the menu is lit (1) or dimmed (0), and what it was reacting to.
    private static float activity;
    private static long litUntil;
    private static long seenPress = Long.MIN_VALUE;
    private static long activityFrame;
    private static boolean wasDrawn;

    // Per level (categories, modules, settings): how far it is open, and where its band is.
    private static final float[] OPEN = new float[3];
    private static final boolean[] CLOSING = new boolean[3];
    private static final float[] BAND = new float[3];
    private static float editing;
    private static long revealStart;
    private static State lastState;

    private ClassicTabGui() {}

    /** What the menu shows, to tell when a key has changed it. */
    private record State(int category, int module, boolean open, boolean inSettings, int setting, boolean editing) {}

    /** Called for a frame that does not draw the menu. */
    static void hidden() {
        wasDrawn = false;
        lastState = null;
        OPEN[1] = 0.0F;
        OPEN[2] = 0.0F;
        editing = 0.0F;
    }

    /**
     * How lit the menu is, 0 to 1: it comes up with a key press and, four seconds on, dims again. Also what the watermark
     * follows, so this is asked every frame, drawn or not.
     */
    static float activity(final @Nullable TabGui tab) {
        long now = System.nanoTime();
        float dt = activityFrame == 0L ? 0.0F : Math.min(0.05F, (now - activityFrame) / 1.0E9F);
        activityFrame = now;
        if (tab != null) {
            if (!wasDrawn) {
                litUntil = now + LIT_AFTER_ENABLE;
            }
            if (tab.lastPress() != seenPress) {
                seenPress = tab.lastPress();
                litUntil = now + LIT_AFTER_PRESS;
            }
        }

        activity = LegacyHud.approach(activity, tab != null && now < litUntil ? 1.0F : 0.0F, dt / 0.2F);
        return activity;
    }

    static void render(final LegacyCanvas c, final TabGui tab, final float dt, final boolean interactive) {
        if (interactive) {
            tab.markShown(LAYOUT);
        }
        List<ModuleCategory> categories = tab.categories();
        if (categories.isEmpty()) {
            hidden();
            return;
        }

        float strength = 0.5F + 0.5F * activity;
        int selectedCategory = Math.floorMod(tab.selectedCategory(), categories.size());
        List<Module> modules = tab.modules(categories.get(selectedCategory));
        int selectedModule = modules.isEmpty() ? 0 : Math.floorMod(tab.selectedModule(), modules.size());
        Module module = modules.isEmpty() ? null : modules.get(selectedModule);
        List<Setting<?>> settings = module == null ? List.of() : tab.settings(module);
        int selectedSetting = settings.isEmpty() ? 0 : Math.floorMod(tab.selectedSetting(), settings.size());

        boolean open = tab.isOpen() && module != null;
        boolean inSettings = open && tab.inSettings() && !settings.isEmpty();
        State state = new State(selectedCategory, selectedModule, open, inSettings, selectedSetting, tab.isEditing());
        boolean first = !wasDrawn;
        if (!state.equals(lastState)) {
            revealStart = System.nanoTime();
            lastState = state;
        }
        wasDrawn = true;

        // What each level lists, and which row is on.
        List<String> level0 = new ArrayList<>();
        for (ModuleCategory category : categories) {
            level0.add(category.toString());
        }
        List<String> level1 = new ArrayList<>();
        List<Boolean> level1Off = new ArrayList<>();
        for (Module listed : modules) {
            level1.add(listed.getName());
            level1Off.add(!listed.isEnabled());
        }
        List<String> level2 = new ArrayList<>();
        for (Setting<?> setting : settings) {
            level2.add(setting.getName() + " " + valueOf(setting));
        }

        float[] target = {1.0F, open ? 1.0F : 0.0F, inSettings ? 1.0F : 0.0F};
        for (int level = 0; level < 3; level++) {
            if (first) {
                OPEN[level] = target[level];
            }
            CLOSING[level] = target[level] < OPEN[level];
            OPEN[level] = LegacyHud.approach(OPEN[level], target[level], dt / 0.3F);
        }
        int[] selected = {selectedCategory, selectedModule, selectedSetting};
        List<List<String>> lists = List.of(level0, level1, level2);

        int x = X;
        int[] panelX = new int[3];
        int[] panelWidth = new int[3];
        for (int level = 0; level < 3; level++) {
            panelX[level] = x;
            panelWidth[level] = width(c, lists.get(level));
            x += panelWidth[level] + GAP;
        }

        for (int level = 0; level < 3; level++) {
            if (OPEN[level] <= 0.0F || lists.get(level).isEmpty()) {
                if (OPEN[level] <= 0.0F) {
                    BAND[level] = selected[level] * ROW;
                }
                continue;
            }

            float grow = CLOSING[level] ? Easing.easeInCubic(OPEN[level], 0.0F, 1.0F, 1.0F) : Easing.easeOutCubic(OPEN[level], 0.0F, 1.0F, 1.0F);
            List<String> rows = lists.get(level);
            int height = rows.size() * ROW + 2 * PAD;
            BAND[level] = LegacyHud.smooth(BAND[level], selected[level] * ROW, dt, 18.0F);

            c.scissor(panelX[level], TOP, panelX[level] + Math.round(panelWidth[level] * grow), TOP + height);
            try {
                c.fill(panelX[level], TOP, panelX[level] + panelWidth[level], TOP + height, LegacyCanvas.alpha(PANEL, 0.6F * strength));
                int bandTop = TOP + PAD + Math.round(BAND[level]);
                c.gradientH(panelX[level] + PAD, bandTop, panelX[level] + panelWidth[level] - PAD, bandTop + ROW, BAND_LEFT, BAND_RIGHT);
                for (int i = 0; i < rows.size(); i++) {
                    boolean off = level == 1 && level1Off.get(i);
                    int color = LegacyCanvas.alpha(off ? GREY : TEXT, Math.min(1.0F, strength * 1.7F));
                    c.text(FACE, SIZE, rows.get(i), panelX[level] + 7, TOP + 6 + i * ROW, color);
                }
            } finally {
                c.unscissor();
            }
        }
        c.fill(12, TOP, 102, TOP + 1, TEXT);

        // The box beside the deepest panel says what the highlighted module or setting is for.
        int deepest = inSettings ? 2 : open ? 1 : -1;
        if (deepest < 0) {
            editing = 0.0F;
            return;
        }

        boolean editingNow = inSettings && tab.isEditing();
        editing = LegacyHud.approach(editing, editingNow ? 1.0F : 0.0F, dt / 0.3F);
        float pointer = editingNow ? Easing.easeOutCubic(editing, 0.0F, 1.0F, 1.0F) * activity
                : Easing.easeInCubic(editing, 0.0F, 1.0F, 1.0F);
        String description = deepest == 2 ? settings.get(selectedSetting).getDescription() : module.getDescription();
        float reveal = Easing.easeOutCubic(Math.min(1.0F, (System.nanoTime() - revealStart) / 500.0E6F), 0.0F, 1.0F, 1.0F);
        int rowTop = TOP + ROW * selected[deepest] + PAD;
        int tipX = panelX[deepest] + panelWidth[deepest];
        c.pointerLeft(tipX + 14.0F * pointer - 12.0F * pointer, TOP + 16.0F + ROW * selected[deepest], 24.0F * pointer,
                LegacyCanvas.alpha(PANEL, 0.6F * strength));
        int boxX = tipX + 4 + Math.round(pointer * 28.0F);
        int boxWidth = Math.round((c.textWidth(FACE, SIZE, description) + 8.0F) * reveal);
        c.fill(boxX, rowTop, boxX + boxWidth, rowTop + ROW, LegacyCanvas.alpha(PANEL, 0.6F * strength));
        c.scissor(boxX, rowTop, boxX + boxWidth, rowTop + ROW);
        try {
            c.text(FACE, SIZE, description, boxX + 4, rowTop + 2, LegacyCanvas.alpha(TEXT, Math.min(1.0F, strength * 1.7F)));
        } finally {
            c.unscissor();
        }
    }

    /** A panel is at least 106 wide and otherwise as wide as its longest row and 14 of margin. */
    private static int width(final LegacyCanvas c, final List<String> rows) {
        float widest = 0.0F;
        for (String row : rows) {
            widest = Math.max(widest, c.textWidth(FACE, SIZE, row));
        }

        return Math.max(MIN_WIDTH, (int) widest + 14);
    }

    /** A setting's value as the old menu wrote it after the name. */
    static String valueOf(final Setting<?> setting) {
        if (setting instanceof BooleanSetting bool) {
            return Boolean.toString(bool.get());
        }
        if (setting instanceof NumberSetting number) {
            return number.getDecimalPlaces() == 0 ? Integer.toString(number.getInt()) : String.format("%." + number.getDecimalPlaces() + "f", number.get());
        }
        if (setting instanceof ColorSetting color) {
            return String.format("#%06X", color.get() & 0xFFFFFF);
        }
        Object value = setting.get();
        return value instanceof Enum<?> choice ? com.mentalfrostbyte.jello.setting.EnumSetting.label(choice) : String.valueOf(value);
    }
}
