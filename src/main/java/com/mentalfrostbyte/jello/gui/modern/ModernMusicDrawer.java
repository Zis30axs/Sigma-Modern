package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.music.MusicPlayer;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;

/**
 * SigmaModern's music player as its own window, tucked away past the right edge of the screen with only a
 * small glass tab showing. It has to be pulled out by hand: grab the tab (or, once out, the window's title bar)
 * and drag it left; let go and it springs fully open or back shut, carrying the flick's momentum. A click
 * without a drag just nudges it out and back, to show that it moves. Whether it was left open is remembered
 * across screens, like any window.
 *
 * <p>A host screen gives it a vertical band each frame (the same band for drawing and for input) and routes
 * mouse and key events to it first. It draws in its own GUI stratum, so the screen's text never shows
 * through it. While its search field has focus ({@link #isTyping()}) it takes every key and character, so a
 * host must ask it before running its own shortcuts.</p>
 */
final class ModernMusicDrawer {
    static final int TAB_W = 15, TAB_H = 74;
    private static final int MARGIN = 10;
    private static final float STIFFNESS = 170F, DAMPING = 19F;

    // Where the window sits is the window's own state, not the screen's: it stays out across screens.
    private static boolean open = "open".equalsIgnoreCase(System.getProperty("sigma.debug.musicDrawer"));
    private static float openness = open ? 1F : 0F;
    private static float velocity;

    private final ModernMusicView view;
    private final int width;
    private final float[] tabBars = new float[3];
    private int screenW, top, bottom;
    private boolean dragging, moved;
    private double pressX;
    private float grabOffset;
    private long lastDragAt;
    private float dragVelocity;
    private float tabHover, hint;
    private long lastFrame;
    private float time;
    private int lastMouseX = -1, lastMouseY = -1;

    ModernMusicDrawer(int width) {
        this.width = width;
        this.view = new ModernMusicView(player());
    }

    private static MusicPlayer player() {
        return Client.getInstance().getMusicPlayer();
    }

    // --- geometry -----------------------------------------------------------------------------------

    /** The host's band for this frame: the screen width and the window's top and bottom. */
    void place(int screenW, int top, int bottom) {
        this.screenW = screenW;
        this.top = top;
        this.bottom = Math.max(top + 60, bottom);
    }

    private float travel() {
        return this.width + MARGIN;
    }

    private int windowX() {
        return Math.round(this.screenW - Math.max(-0.05F, openness) * travel());
    }

    private ModernMusicView.Box tab() {
        int mid = (this.top + this.bottom) / 2;
        return new ModernMusicView.Box(windowX() - TAB_W, mid - TAB_H / 2, TAB_W, TAB_H);
    }

    private ModernMusicView.Box window() {
        return new ModernMusicView.Box(windowX(), this.top, this.width, this.bottom - this.top);
    }

    private boolean visible() {
        return openness > 0.01F;
    }

    /** Pixels the window takes from the right of the screen right now, so a host can make room for it. */
    float reserve() {
        return Math.max(0F, Math.min(1F, openness)) * (travel() + MARGIN);
    }

    boolean isOpen() {
        return open;
    }

    boolean contains(double mx, double my) {
        return tab().contains(mx, my) || (visible() && window().contains(mx, my));
    }

    // --- rendering ------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor g, int mx, int my) {
        long now = System.nanoTime();
        float dt = this.lastFrame == 0L ? 0F : Math.min(0.05F, (now - this.lastFrame) / 1_000_000_000F);
        this.lastFrame = now;
        this.time += dt;
        this.lastMouseX = mx;
        this.lastMouseY = my;
        if (!this.dragging) spring(dt);
        if (!open && !this.dragging) this.view.blur();

        g.nextStratum();
        ModernMusicView.Box tab = tab();
        boolean overTab = tab.contains(mx, my) || this.dragging;
        this.tabHover = ModernStyle.smooth(this.tabHover, overTab ? 1F : 0F, dt, 14F);
        this.hint = ModernStyle.smooth(this.hint, overTab && !open && !this.dragging ? 1F : 0F, dt, overTab ? 5F : 12F);
        if (overTab) g.requestCursor(CursorTypes.RESIZE_EW);
        drawTab(g, tab, dt);

        if (visible()) {
            ModernMusicView.Box window = window();
            boolean over = window.contains(mx, my);
            if (over && onHandle(window, mx, my)) g.requestCursor(CursorTypes.RESIZE_EW);
            this.view.render(g, window.x(), window.y(), window.w(), window.h(), over ? mx : -10000, over ? my : -10000, dt, this.time);
        }
        if (this.hint > 0.02F) drawHint(g, tab);
    }

    /** The spring that carries the window to open or shut, overshooting a touch. */
    private void spring(float dt) {
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

    private void drawTab(GuiGraphicsExtractor g, ModernMusicView.Box tab, float dt) {
        // Pokes a little further out under the pointer; extends under the window so only its left side shows.
        int peek = Math.round(3F * this.tabHover);
        int x = tab.x() - peek, y = tab.y(), w = tab.w() + peek + 12, h = tab.h();
        ModernStyle.dropShadow(g, x, y + 2, w, h, 8, 0.6F);
        ModernStyle.rounded(g, x, y, w, h, 8, ModernStyle.mix(0x80EDFAFF, 0xB3EDFAFF, this.tabHover));
        ModernStyle.rounded(g, x + 1, y + 1, w - 2, h - 2, 7, ModernStyle.mix(0xE0112536, 0xF0173348, this.tabHover));
        float cx = x + (tab.w() + peek) / 2F;
        // A chevron pointing the way it will move, the equalizer in the middle, a grip below.
        float chevron = open ? (float)(Math.PI / 2) : (float)(-Math.PI / 2);
        ModernIcons.drawRotated(g, ModernIcons.Icon.CHEVRON_UP, cx, y + 12F, 9F, chevron, ModernStyle.mix(0xFF9FC4DA, 0xFFFFFFFF, this.tabHover));
        ModernMusicView.soundBars(g, this.tabBars, cx - 4F, y + h / 2F, 13F, 2F, 1F, 0xFFA9E2FF, player().isPlaying(), dt, this.time);
        for (int i = 0; i < 3; i++) {
            ModernStyle.fill(g, Math.round(cx - 3F), y + h - 17 + i * 3, Math.round(cx + 3F), y + h - 16 + i * 3, 0x66CDEBFF);
        }
    }

    private void drawHint(GuiGraphicsExtractor g, ModernMusicView.Box tab) {
        String text = ModernText.t("Drag out the music player", "拖出音乐播放器");
        float tw = ModernTypography.width(ModernTypography.Face.TEXT, text, 0.9F);
        int w = Math.round(tw) + 16, h = 18;
        int x = Math.round(tab.x() - 8 - w + (1F - this.hint) * 6F), y = tab.y() + tab.h() / 2 - h / 2;
        try (var fade = ModernStyle.alphaScope(this.hint)) {
            ModernStyle.darkGlass(g, x, y, w, h, 9, 0xE6112536);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, text, x + 8F, y + 4.5F, 0.9F, 0xFFE3F2FA);
        }
    }

    // --- input ------------------------------------------------------------------------------------

    /** True when the event landed on the tab or the window. */
    boolean mouseClicked(double mx, double my, int button) {
        boolean onTab = tab().contains(mx, my);
        ModernMusicView.Box window = window();
        boolean onHeader = visible() && onHandle(window, mx, my);
        if (button == 0 && (onTab || onHeader)) {
            this.dragging = true;
            this.moved = false;
            this.pressX = mx;
            this.grabOffset = (float)(mx - windowX());
            this.lastDragAt = System.nanoTime();
            this.dragVelocity = 0F;
            velocity = 0F;
            return true;
        }
        if (onTab) return true;
        if (visible() && window.contains(mx, my)) {
            this.view.mouseClicked(window.x(), window.y(), window.w(), window.h(), mx, my, button);
            return true;
        }
        this.view.blur();
        return false;
    }

    /** The title bar grabs the window, except where it has a control of its own. */
    private boolean onHandle(ModernMusicView.Box window, double mx, double my) {
        return ModernMusicView.header(window.x(), window.y(), window.w()).contains(mx, my)
            && !this.view.headerControlAt(window.x(), window.y(), window.w(), window.h(), mx, my);
    }

    boolean mouseDragged(double mx, double my) {
        if (!this.dragging) return visible() && this.view.mouseDragged(mx, my);
        if (Math.abs(mx - this.pressX) > 2.0) this.moved = true;
        float x = (float)(mx - this.grabOffset);
        float next = Math.max(0F, Math.min(1F, (this.screenW - x) / travel()));
        long now = System.nanoTime();
        float seconds = Math.max(0.004F, (now - this.lastDragAt) / 1_000_000_000F);
        this.lastDragAt = now;
        float instant = Math.max(-12F, Math.min(12F, (next - openness) / seconds));
        this.dragVelocity = this.dragVelocity * 0.4F + instant * 0.6F;
        openness = next;
        return true;
    }

    /** Ends a drag: a flick decides by its direction, a slow release by how far out the window is. */
    boolean mouseReleased() {
        this.view.mouseReleased();
        if (!this.dragging) return false;
        this.dragging = false;
        if (!this.moved) {
            // A click, not a drag: nudge out and back (or, when open, a little shove in and back).
            velocity = open ? -1.6F : 2.4F;
            return true;
        }
        if (this.dragVelocity > 1.2F) open = true;
        else if (this.dragVelocity < -1.2F) open = false;
        else open = openness > 0.45F;
        velocity = this.dragVelocity * 0.5F;
        return true;
    }

    boolean mouseScrolled(double mx, double my, double amount) {
        ModernMusicView.Box window = window();
        if (!visible() || !window.contains(mx, my)) return false;
        this.view.mouseScrolled(window.x(), window.y(), window.w(), window.h(), mx, my, amount);
        return true;
    }

    /** The search field has focus: keys and characters are the window's, wherever the pointer is. */
    boolean isTyping() {
        return open && visible() && this.view.isTyping();
    }

    /** While typing every key is the field's; otherwise media keys work while the pointer rests on the window. */
    boolean keyPressed(KeyEvent e) {
        if (isTyping()) return this.view.keyPressed(e);
        return visible() && window().contains(this.lastMouseX, this.lastMouseY) && this.view.keyPressed(e);
    }

    boolean charTyped(CharacterEvent e) {
        return isTyping() && this.view.charTyped(e);
    }

    /** IME composition, while the search field has focus. */
    boolean preeditUpdated(@org.jspecify.annotations.Nullable PreeditEvent e) {
        return isTyping() && this.view.preeditUpdated(e);
    }

    /** The host screen is going away: the field lets go of the keyboard (and of the IME). */
    void blur() {
        this.view.blur();
    }

    // --- test and debug seams ---------------------------------------------------------------------------

    int[] tabBounds() {
        ModernMusicView.Box tab = tab();
        return new int[]{tab.x(), tab.y(), tab.w(), tab.h()};
    }

    int[] control(String name) {
        ModernMusicView.Box window = window();
        return this.view.control(name, window.x(), window.y(), window.w(), window.h());
    }

    /** Opens or shuts the window at once, without the spring (debug previews, test cleanup). */
    static void setOpenNow(boolean value) {
        open = value;
        openness = value ? 1F : 0F;
        velocity = 0F;
    }
}
