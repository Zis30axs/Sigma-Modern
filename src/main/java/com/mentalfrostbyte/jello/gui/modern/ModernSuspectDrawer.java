package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.anticheat.alert.Suspects;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.SuspectList;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * SigmaModern's presentation of the {@link SuspectList} module: a drawer tucked past the left edge of the screen
 * with only a small glass tab showing, the mirror of the music player's on the right. It is pulled out by hand:
 * grab the tab (or, once out, the window's title bar) and drag it right; let go and it springs fully open or
 * back shut, carrying the flick's momentum. A click without a drag just nudges it out and back. Whether it was
 * left open is remembered across screens.
 *
 * <p>It is resident: not part of any one screen. {@code Gui} draws it over whichever screen is open while a
 * world is loaded, {@code ModernHud} draws it over the game while it is left open (the module's
 * {@code ShowOnHud}), and {@code MouseHandler} offers it every mouse event first. Nothing is drawn or
 * consumed when the module is off, when the presentation is not SigmaModern, or with no world loaded.</p>
 *
 * <p>It only reads the module; the list itself and what forgetting a suspect means belong to
 * {@link SuspectList}, so another presentation can draw the same data its own way.</p>
 */
public final class ModernSuspectDrawer {
    static final int TAB_W = 15, TAB_H = 74;
    static final int WIDTH = 268;
    private static final int MARGIN = 10;
    private static final float STIFFNESS = 170F, DAMPING = 19F;
    private static final int HEADER_H = 46, ROW_H = 54, PAD = 8, BIN = 18;
    private static final int MIN_ROWS = 3, MIN_HEIGHT = 210;
    private static final int WINDOW_SURFACE = 0xEB0A1B29;
    /** Below the caption strip, and clear of the bottom edge where the chat input sits. */
    private static final int BOTTOM_GAP = 40;

    // Where the drawer sits is its own state, not any screen's: it stays out across screens.
    private static boolean open;
    private static float openness;
    private static float velocity;

    private static boolean dragging, moved;
    private static double pressX;
    private static float grabOffset;
    private static long lastDragAt;
    private static float dragVelocity;
    private static float tabHover, hint;
    private static long lastFrame;
    private static float time;
    private static int scroll;
    private static int screenW, top, bottom;
    /** How many suspects the window was last sized for; the geometry below follows it. */
    private static int suspectCount;

    private ModernSuspectDrawer() {}

    // --- when it exists -----------------------------------------------------------------------------

    /** The module, when the drawer should exist at all: switched on, SigmaModern, and a world loaded. */
    private static @Nullable SuspectList list() {
        SuspectList list = Modules.enabled(SuspectList.class);
        if (list == null || !ModernHud.isActive() || Minecraft.getInstance().level == null) {
            dragging = false;
            return null;
        }
        return list;
    }

    // --- geometry -----------------------------------------------------------------------------------

    private static void place(int width, int height) {
        screenW = width;
        top = ModernWindowFrame.reservedTop() + 10;
        bottom = Math.max(top + 120, height - BOTTOM_GAP);
    }

    private static float travel() {
        return WIDTH + MARGIN;
    }

    /** The window's right edge: past the screen's left edge when shut, {@code WIDTH + MARGIN} out when open. */
    static int windowRight(float openness) {
        return Math.round(Math.max(-0.05F, openness) * travel());
    }

    private static int windowX() {
        return windowRight(openness) - WIDTH;
    }

    /** Tall enough for its rows, never shorter than the empty state needs, never taller than the band it sits in. */
    static int windowHeight(int suspects, int bandHeight) {
        int wanted = HEADER_H + PAD + Math.max(MIN_ROWS, suspects) * ROW_H;
        return Math.max(Math.min(wanted, bandHeight), Math.min(MIN_HEIGHT, bandHeight));
    }

    private static ModernMusicView.Box tab() {
        int mid = top + (window().h()) / 2;
        return new ModernMusicView.Box(windowRight(openness), mid - TAB_H / 2, TAB_W, TAB_H);
    }

    private static ModernMusicView.Box window() {
        return new ModernMusicView.Box(windowX(), top, WIDTH, windowHeight(suspectCount, bottom - top));
    }

    private static boolean visible() {
        return openness > 0.01F;
    }

    private static int visibleRows(ModernMusicView.Box window) {
        return Math.max(1, (window.h() - HEADER_H - PAD) / ROW_H);
    }

    private static ModernMusicView.Box clearButton(ModernMusicView.Box window) {
        return new ModernMusicView.Box(window.x() + window.w() - PAD - 58, window.y() + 12, 52, 20);
    }

    private static ModernMusicView.Box header(ModernMusicView.Box window) {
        return new ModernMusicView.Box(window.x(), window.y(), window.w(), HEADER_H);
    }

    private static ModernMusicView.Box row(ModernMusicView.Box window, int index) {
        return new ModernMusicView.Box(window.x() + PAD, window.y() + HEADER_H + index * ROW_H, window.w() - 2 * PAD, ROW_H - 4);
    }

    private static ModernMusicView.Box bin(ModernMusicView.Box row) {
        return new ModernMusicView.Box(row.x() + row.w() - BIN - 6, row.y() + 5, BIN, BIN);
    }

    /** Whether letting go here leaves the drawer open: a flick decides by its direction, a slow release by how far out it is. */
    static boolean settleOpen(float dragVelocity, float openness) {
        if (dragVelocity > 1.2F) return true;
        if (dragVelocity < -1.2F) return false;
        return openness > 0.45F;
    }

    // --- rendering ------------------------------------------------------------------------------------

    /** Over whichever screen is open: the tab, and the window while it is out. */
    public static void renderOverScreen(GuiGraphicsExtractor g, int mx, int my) {
        SuspectList list = list();
        if (list == null) return;
        place(g.guiWidth(), g.guiHeight());
        List<Suspects.Entry> suspects = list.ranked();
        suspectCount = suspects.size();
        float dt = frame();
        if (!dragging) spring(dt);

        g.nextStratum();
        ModernMusicView.Box tab = tab();
        boolean overTab = tab.contains(mx, my) || dragging;
        tabHover = ModernStyle.smooth(tabHover, overTab ? 1F : 0F, dt, 14F);
        hint = ModernStyle.smooth(hint, overTab && !open && !dragging ? 1F : 0F, dt, overTab ? 5F : 12F);
        if (overTab) g.requestCursor(CursorTypes.RESIZE_EW);
        drawTab(g, tab, suspects);

        if (visible()) {
            ModernMusicView.Box window = window();
            boolean over = window.contains(mx, my);
            if (over && header(window).contains(mx, my) && !clearButton(window).contains(mx, my)) g.requestCursor(CursorTypes.RESIZE_EW);
            drawWindow(g, window, list, suspects, over ? mx : -10000, over ? my : -10000);
        }
        if (hint > 0.02F) drawHint(g, tab);
    }

    /** Over the game itself, while the drawer is left out and the module asks for it; nothing to grab, so no tab. */
    public static void renderHud(GuiGraphicsExtractor g) {
        SuspectList list = list();
        if (list == null || !list.showOnHud()) return;
        place(g.guiWidth(), g.guiHeight());
        List<Suspects.Entry> suspects = list.ranked();
        suspectCount = suspects.size();
        float dt = frame();
        spring(dt);
        if (!visible()) return;
        drawWindow(g, window(), list, suspects, -10000, -10000);
    }

    private static float frame() {
        long now = System.nanoTime();
        float dt = lastFrame == 0L ? 0F : Math.min(0.05F, (now - lastFrame) / 1_000_000_000F);
        lastFrame = now;
        time += dt;
        return dt;
    }

    /** The spring that carries the window to open or shut, overshooting a touch. */
    private static void spring(float dt) {
        float target = open ? 1F : 0F;
        for (int i = 0; i < 4; i++) {
            float step = dt / 4F;
            velocity += (STIFFNESS * (target - openness) - DAMPING * velocity) * step;
            openness += velocity * step;
        }
        if (Math.abs(target - openness) < 0.002F && Math.abs(velocity) < 0.01F) {
            openness = target;
            velocity = 0F;
        }
    }

    private static void drawTab(GuiGraphicsExtractor g, ModernMusicView.Box tab, List<Suspects.Entry> suspects) {
        // Pokes a little further out under the pointer; extends under the window so only its right side shows.
        int peek = Math.round(3F * tabHover);
        int x = tab.x() - 6, y = tab.y(), w = tab.w() + peek + 6, h = tab.h();
        ModernStyle.dropShadow(g, x, y + 2, w, h, 8, 0.6F);
        ModernStyle.rounded(g, x, y, w, h, 8, ModernStyle.mix(0x80EDFAFF, 0xB3EDFAFF, tabHover));
        ModernStyle.rounded(g, x + 1, y + 1, w - 2, h - 2, 7, ModernStyle.mix(0xE0112536, 0xF0173348, tabHover));
        float cx = x + 6 + (tab.w() + peek) / 2F;
        // A chevron pointing the way it will move, the warning mark in the middle, a grip below.
        float chevron = open ? (float) (-Math.PI / 2) : (float) (Math.PI / 2);
        ModernIcons.drawRotated(g, ModernIcons.Icon.CHEVRON_UP, cx, y + 12F, 9F, chevron, ModernStyle.mix(0xFF9FC4DA, 0xFFFFFFFF, tabHover));
        int tone = suspects.isEmpty() ? 0xFF7F9FB4 : tone(suspects.get(0).total());
        ModernIcons.draw(g, ModernIcons.Icon.WARNING, cx - 5.5F, y + h / 2F - 9F, 11F, tone);
        if (!suspects.isEmpty()) {
            String count = Integer.toString(suspects.size());
            float cw = ModernTypography.width(ModernTypography.Face.TEXT, count, 0.9F);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, count, cx - cw / 2F, y + h / 2F + 5F, 0.9F, tone);
        }
        for (int i = 0; i < 3; i++) {
            ModernStyle.fill(g, Math.round(cx - 3F), y + h - 17 + i * 3, Math.round(cx + 3F), y + h - 16 + i * 3, 0x66CDEBFF);
        }
    }

    private static void drawHint(GuiGraphicsExtractor g, ModernMusicView.Box tab) {
        String text = ModernText.t("Drag out the suspect list", "拖出可疑玩家名单");
        float tw = ModernTypography.width(ModernTypography.Face.TEXT, text, 0.9F);
        int w = Math.round(tw) + 16, h = 18;
        int x = Math.round(tab.x() + tab.w() + 8 - (1F - hint) * 6F), y = tab.y() + tab.h() / 2 - h / 2;
        try (var fade = ModernStyle.alphaScope(hint)) {
            ModernStyle.darkGlass(g, x, y, w, h, 9, 0xE6112536);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, text, x + 8F, y + 4.5F, 0.9F, 0xFFE3F2FA);
        }
    }

    private static void drawWindow(GuiGraphicsExtractor g, ModernMusicView.Box window, SuspectList list,
                                   List<Suspects.Entry> suspects, int mx, int my) {
        int x = window.x(), y = window.y(), w = window.w(), h = window.h();
        ModernStyle.dropShadow(g, x, y + 2, w, h, 14, 0.7F);
        // Denser than the full-screen pages' cards: this one sits over the game and must stay legible there.
        ModernStyle.darkGlass(g, x, y, w, h, 14, WINDOW_SURFACE);
        ModernStyle.fill(g, x + 14, y + 1, x + w - 14, y + 2, 0x2EDDF3FF);
        ModernPage.header(g, x + 14F, y + 12F, "ANTICHEAT", ModernText.t("Suspects", "可疑玩家"), 1.15F);

        ModernMusicView.Box clear = clearButton(window);
        boolean overClear = clear.contains(mx, my);
        if (!suspects.isEmpty()) {
            ModernStyle.rounded(g, clear.x(), clear.y(), clear.w(), clear.h(), 10, overClear ? 0x33FFFFFF : 0x14FFFFFF);
            ModernIcons.draw(g, ModernIcons.Icon.TRASH, clear.x() + 7F, clear.y() + 5F, 10F, overClear ? 0xFFFFB8C0 : 0xFF9FC4DA);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernText.t("Clear", "清空"), clear.x() + 21F, clear.y() + 4.5F, 0.9F,
                overClear ? 0xFFFFFFFF : 0xFFC4DDEB);
        }
        ModernStyle.fill(g, x + 12, y + HEADER_H - 6, x + w - 12, y + HEADER_H - 5, 0x1AFFFFFF);

        int rows = visibleRows(window);
        scroll = Math.max(0, Math.min(Math.max(0, suspects.size() - rows), scroll));
        if (suspects.isEmpty()) {
            drawEmpty(g, window, list);
            return;
        }
        for (int i = 0; i < rows && scroll + i < suspects.size(); i++) {
            drawRow(g, row(window, i), suspects.get(scroll + i), mx, my);
        }
        if (suspects.size() > rows) drawScrollbar(g, window, suspects.size(), rows);
    }

    private static void drawEmpty(GuiGraphicsExtractor g, ModernMusicView.Box window, SuspectList list) {
        float cx = window.x() + window.w() / 2F;
        float cy = window.y() + HEADER_H + (window.h() - HEADER_H) / 2F - 24F;
        float breathe = 0.2F + 0.06F * (float) Math.sin(time * 1.3F);
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, cx - 36F, cy - 36F, 72F, Math.round(255 * breathe) << 24 | 0xBFE8FF);
        ModernIcons.draw(g, list.sourceEnabled() ? ModernIcons.Icon.CHECK : ModernIcons.Icon.WARNING, cx - 13F, cy - 13F, 26F, 0xFFBFE3F5);
        centered(g, ModernTypography.Face.DISPLAY_ITALIC, ModernText.t("No one flagged", "暂无可疑玩家"), cx, cy + 24F, 1.3F, 0xFFE3F2FA);
        String hint = list.sourceEnabled()
            ? ModernText.t("Players show up once a check reaches the alert level.", "检测达到告警等级后，玩家会出现在这里。")
            : ModernText.t("AntiCheat is off - turn it on to start watching.", "AntiCheat 未开启，开启后才会开始检测。");
        String fitted = ModernTypography.ellipsize(hint, window.w() - 24);
        centered(g, ModernTypography.Face.TEXT, fitted, cx, cy + 42F, 0.9F, 0xFF8FB0C4);
    }

    private static void centered(GuiGraphicsExtractor g, ModernTypography.Face face, String text, float cx, float y, float scale, int color) {
        ModernTypography.draw(g, face, text, cx - ModernTypography.width(face, text, scale) / 2F, y, scale, color);
    }

    private static void drawRow(GuiGraphicsExtractor g, ModernMusicView.Box row, Suspects.Entry entry, int mx, int my) {
        int x = row.x(), y = row.y(), w = row.w();
        boolean hover = row.contains(mx, my);
        ModernStyle.rounded(g, x, y, w, row.h(), 10, hover ? 0x22FFFFFF : 0x0FFFFFFF);

        float total = (float) entry.total();
        int tone = tone(total);
        ModernStyle.fill(g, x + 1, y + 8, x + 3, y + row.h() - 8, tone);

        ModernMusicView.Box bin = bin(row);
        String level = String.format(Locale.ROOT, "VL %.1f", total);
        float levelW = ModernTypography.width(ModernTypography.Face.TEXT, level, 0.95F);
        float levelX = bin.x() - 6F - levelW;
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(entry.name(), Math.max(30, Math.round(levelX - (x + 12) - 6))),
            x + 12F, y + 6F, 1.05F, 0xFFF2F8FC);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, level, levelX, y + 7F, 0.95F, tone);
        boolean overBin = bin.contains(mx, my);
        ModernIcons.draw(g, ModernIcons.Icon.TRASH, bin.x() + 3F, bin.y() + 3F, 12F, overBin ? 0xFFFFB8C0 : 0xFF7F9FB4);

        int chipX = x + 12;
        int chipY = y + 22;
        int shown = 0;
        for (Map.Entry<String, Double> check : entry.levels().entrySet()) {
            String text = String.format(Locale.ROOT, "%s %.1f", check.getKey(), check.getValue());
            int chipW = Math.round(ModernTypography.width(ModernTypography.Face.TEXT, text, 0.9F)) + 12;
            if (chipX + chipW > x + w - 8) break;
            chipX += ModernPage.chip(g, chipX, chipY, text, chipText(check.getValue()), chipFill(check.getValue())) + 4;
            shown++;
        }
        int hidden = entry.levels().size() - shown;
        if (hidden > 0) {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, "+" + hidden, chipX + 2F, chipY + 2F, 0.9F, 0xFF7F9FB4);
        }

        String detail = describe(entry);
        if (!detail.isEmpty()) {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.ellipsize(detail, w - 24), x + 12F, y + 38F, 0.85F, 0xFF7F9FB4);
        }
    }

    private static void drawScrollbar(GuiGraphicsExtractor g, ModernMusicView.Box window, int total, int rows) {
        int trackTop = window.y() + HEADER_H;
        int trackH = rows * ROW_H - 4;
        int thumbH = Math.max(18, trackH * rows / total);
        int thumbY = trackTop + (trackH - thumbH) * scroll / Math.max(1, total - rows);
        int x = window.x() + window.w() - 5;
        ModernStyle.rounded(g, x, trackTop, 2, trackH, 1, 0x14FFFFFF);
        ModernStyle.rounded(g, x, thumbY, 2, thumbH, 1, 0x66BFE8FF);
    }

    private static String describe(Suspects.Entry entry) {
        if (entry.lastAlertNanos() == Long.MIN_VALUE) return "";
        long seconds = Math.max(0L, (System.nanoTime() - entry.lastAlertNanos()) / 1_000_000_000L);
        String ago = seconds < 60 ? seconds + "s" : (seconds / 60) + "m";
        return entry.lastCheck() + ": " + entry.lastDetail() + " - " + ago;
    }

    private static int tone(double total) {
        return total >= 6.0 ? 0xFFFF8A96 : total >= 3.0 ? 0xFFF2C46B : 0xFF9DC4DB;
    }

    private static int chipText(double level) {
        return level >= 6.0 ? 0xFFFFD0D5 : level >= 3.0 ? 0xFFFFE6A8 : 0xFFD6F1FF;
    }

    private static int chipFill(double level) {
        return level >= 6.0 ? 0x59E24556 : level >= 3.0 ? 0x40F2C46B : 0x262E9BD6;
    }

    // --- input ------------------------------------------------------------------------------------

    /** True when the press landed on the tab or the window, which then owns it. */
    public static boolean mouseClicked(double mx, double my, int button) {
        SuspectList list = list();
        if (list == null) return false;
        place(Minecraft.getInstance().getWindow().getGuiScaledWidth(), Minecraft.getInstance().getWindow().getGuiScaledHeight());
        suspectCount = list.ranked().size();
        boolean onTab = tab().contains(mx, my);
        ModernMusicView.Box window = window();
        boolean onHeader = visible() && header(window).contains(mx, my) && !clearButton(window).contains(mx, my);
        if (button == 0 && (onTab || onHeader)) {
            dragging = true;
            moved = false;
            pressX = mx;
            grabOffset = (float) (mx - windowRight(openness));
            lastDragAt = System.nanoTime();
            dragVelocity = 0F;
            velocity = 0F;
            return true;
        }
        if (onTab) return true;
        if (!visible() || !window.contains(mx, my)) return false;
        if (button == 0) {
            if (clearButton(window).contains(mx, my)) {
                list.forgetAll();
                return true;
            }
            List<Suspects.Entry> suspects = list.ranked();
            for (int i = 0; i < visibleRows(window) && scroll + i < suspects.size(); i++) {
                if (bin(row(window, i)).contains(mx, my)) {
                    list.forget(suspects.get(scroll + i).uuid());
                    return true;
                }
            }
        }
        return true;
    }

    /** True while the drawer is being dragged. */
    public static boolean mouseDragged(double mx, double my) {
        if (!dragging) return false;
        if (Math.abs(mx - pressX) > 2.0) moved = true;
        float right = (float) (mx - grabOffset);
        float next = Math.max(0F, Math.min(1F, right / travel()));
        long now = System.nanoTime();
        float seconds = Math.max(0.004F, (now - lastDragAt) / 1_000_000_000F);
        lastDragAt = now;
        float instant = Math.max(-12F, Math.min(12F, (next - openness) / seconds));
        dragVelocity = dragVelocity * 0.4F + instant * 0.6F;
        openness = next;
        return true;
    }

    /** Ends a drag; true if there was one to end. */
    public static boolean mouseReleased() {
        if (!dragging) return false;
        dragging = false;
        if (!moved) {
            // A click, not a drag: nudge out and back (or, when open, a little shove in and back).
            velocity = open ? -1.6F : 2.4F;
            return true;
        }
        open = settleOpen(dragVelocity, openness);
        velocity = dragVelocity * 0.5F;
        return true;
    }

    /** The wheel scrolls the list while the pointer is over the window. */
    public static boolean mouseScrolled(double mx, double my, double amount) {
        SuspectList list = list();
        if (list == null || !visible() || !window().contains(mx, my)) return false;
        int max = Math.max(0, list.ranked().size() - visibleRows(window()));
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount)));
        return true;
    }

    // --- test and debug seams ---------------------------------------------------------------------------

    /** Opens or shuts the drawer at once, without the spring (debug previews). */
    public static void setOpenNow(boolean value) {
        open = value;
        openness = value ? 1F : 0F;
        velocity = 0F;
    }

    static boolean isOpen() {
        return open;
    }
}
