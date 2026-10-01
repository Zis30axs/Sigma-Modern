package com.mentalfrostbyte.jello.gui.legacy.hud;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.ClientMode;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.gui.modern.ModernHud;
import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.gui.InfoHud;
import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList;
import com.mentalfrostbyte.jello.module.impl.gui.TabGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The in-game HUD of the Jello and Classic presentations, as the old client drew it: the watermark, the module list
 * ({@link JelloActiveMods} / {@link ClassicActiveMods}) and the keyboard menu ({@link JelloTabGui} /
 * {@link ClassicTabGui}).
 *
 * <p>Like the old screens it lays out in framebuffer pixels through {@link LegacyCanvas}, so the numbers below are
 * the old client's. It only draws: what the list names is {@link ModuleArrayList}'s call and what the menu offers and
 * where its selection is belongs to {@link TabGui}, the same modules SigmaModern's HUD ({@link ModernHud}) draws.</p>
 *
 * <p>The old client stacked the left side with an offset event: the watermark took the first 99 pixels, the TabGUI
 * the next 164 and so on. {@link #leftBottom} is that offset, for whatever is drawn below.</p>
 */
public final class LegacyHud {
    /** Where the left stack starts under Jello's watermark: its 104 pixels, less the 5 its soft edge does not use. */
    private static final int JELLO_LEFT_TOP = 99;
    private static final int WATERMARK_W = 170, WATERMARK_H = 104;

    private static long lastFrame;
    private static int leftBottom = JELLO_LEFT_TOP;

    private LegacyHud() {}

    /** Whether the game is showing a presentation whose HUD this draws. */
    public static boolean isActive() {
        ClientMode mode = Client.getInstance().getClientModeManager().get();
        return mode == ClientMode.JELLO || mode == ClientMode.CLASSIC;
    }

    /**
     * How far, in GUI units, the chat is lifted: InfoHUD's character and armor stand in the corner it usually fills, and
     * the old client moved the chat up 40 pixels to clear them. Called from {@code ChatComponent}, so what is drawn and
     * what a click lands on agree.
     */
    public static int chatLift() {
        InfoHud info = Modules.enabled(InfoHud.class);
        if (info == null || !info.movesChat() || Client.getInstance().getClientModeManager().get() != ClientMode.JELLO) {
            return 0;
        }

        return Math.round(80.0F / Math.max(1, Minecraft.getInstance().getWindow().getGuiScale()));
    }

    /** Where the next element down the left side starts, in framebuffer pixels. */
    static int leftBottom() {
        return leftBottom;
    }

    /** Called from a Sigma hook at the end of {@code Hud}'s pass, after the vanilla HUD. */
    public static void render(final GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden() || !isActive()) {
            reset();
            return;
        }

        ClientMode mode = Client.getInstance().getClientModeManager().get();
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            long now = System.nanoTime();
            float dt = lastFrame == 0L ? 0F : Math.min(0.05F, (now - lastFrame) / 1.0E9F);
            lastFrame = now;
            boolean debug = ModernHud.debugShowing();
            boolean jello = mode == ClientMode.JELLO;

            TabGui tab = Modules.enabled(TabGui.class);
            // The menu takes the arrow keys, so it only listens with nothing open; a screen that does not pause the game
            // (the chat, the inventory) leaves the HUD showing, as it was in the old client.
            boolean free = mc.gui.screen() == null;
            boolean showing = free || !mc.gui.screen().isPauseScreen();
            float tabActivity = !showing || debug || jello ? 0F : ClassicTabGui.activity(tab);
            if (jello) {
                watermarkJello(c, debug);
            } else {
                watermarkClassic(c, 0.5F + 0.5F * tabActivity);
            }

            if (!showing) {
                JelloTabGui.hidden();
                ClassicTabGui.hidden();
                JelloActiveMods.reset();
                ClassicActiveMods.reset();
                JelloWidgets.reset();
                return;
            }

            leftBottom = JELLO_LEFT_TOP;
            if (tab == null || debug) {
                JelloTabGui.hidden();
                ClassicTabGui.hidden();
            } else if (jello) {
                ClassicTabGui.hidden();
                leftBottom = JelloTabGui.render(c, tab, JELLO_LEFT_TOP, dt, free) + 10;
            } else {
                JelloTabGui.hidden();
                ClassicTabGui.render(c, tab, dt, free);
            }

            if (jello) {
                JelloWidgets.render(c, mc, leftBottom, debug);
            } else {
                JelloWidgets.reset();
            }

            ModuleArrayList list = Modules.enabled(ModuleArrayList.class);
            if (list == null) {
                JelloActiveMods.reset();
                ClassicActiveMods.reset();
            } else if (jello) {
                ClassicActiveMods.reset();
                JelloActiveMods.render(c, list, rightTop(c, debug, 6));
            } else {
                JelloActiveMods.reset();
                ClassicActiveMods.render(c, list, rightTop(c, false, -2));
            }
        }
    }

    private static void reset() {
        lastFrame = 0L;
        JelloTabGui.hidden();
        ClassicTabGui.hidden();
        JelloActiveMods.reset();
        ClassicActiveMods.reset();
        JelloWidgets.reset();
    }

    /**
     * Where the top-right list starts: {@code start}, or below F3's right column while that is up, and always below
     * vanilla's effect icons, which the list would otherwise draw over.
     */
    private static int rightTop(final LegacyCanvas c, final boolean debug, final int start) {
        int top = start;
        if (debug) {
            int column = ModernHud.debugBottom(false);
            if (column > 0) {
                top = Math.max(top, Math.round((float) LegacyCanvas.toLegacy(column)) + 7);
            }
        }

        int effects = ModernHud.effectIconsBottom(Minecraft.getInstance());
        if (effects > 0) {
            top = Math.max(top, Math.round((float) LegacyCanvas.toLegacy(effects)) + 6);
        }

        return top;
    }

    /** Jello's watermark: a picture in the corner, which moves to the top centre while F3's text is up. */
    private static void watermarkJello(final LegacyCanvas c, final boolean debug) {
        int x = debug ? c.width() / 2 - WATERMARK_W / 2 : 0;
        c.image(LegacyTexture.JELLO_WATERMARK, x, 0, WATERMARK_W, WATERMARK_H);
    }

    /**
     * Classic's: a dark plate with "Sigma" and the version in a moving rainbow, each with a one-pixel shadow. The
     * plate, like the TabGUI's panels, dims to half while nothing is being pressed.
     */
    private static void watermarkClassic(final LegacyCanvas c, final float strength) {
        int plate = LegacyCanvas.alpha(ClassicTabGui.PANEL, 0.6F * strength);
        c.fill(4, 2, 110, 30, plate);
        int shadow = LegacyCanvas.alpha(ClassicTabGui.PANEL, 0.5F * strength);
        c.text(Face.CLASSIC_BOLD, 22, Client.NAME, 9, 2, shadow);
        c.text(Face.CLASSIC_BOLD, 22, Client.NAME, 8, 1, LegacyCanvas.alpha(ClassicTabGui.TEXT, Math.min(1F, strength * 1.2F)));
        int rainbow = java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 4000L) / 4000F, 1F, 1F);
        c.text(Face.CLASSIC_BOLD, 14, Client.RELEASE_TARGET, 73, 2, LegacyCanvas.alpha(ClassicTabGui.PANEL, 0.5F));
        c.text(Face.CLASSIC_BOLD, 14, Client.RELEASE_TARGET, 72, 1, LegacyCanvas.alpha(rainbow, Math.min(1F, strength * 1.4F)));
    }

    /** Eases {@code current} towards {@code target}; {@code rate} is how many times per second it closes the gap. */
    static float smooth(final float current, final float target, final float dt, final float rate) {
        return current + (target - current) * (1F - (float) Math.exp(-rate * dt));
    }

    /** Moves {@code current} towards {@code target} by at most {@code step}. */
    static float approach(final float current, final float target, final float step) {
        if (current < target) {
            return Math.min(target, current + step);
        }

        return Math.max(target, current - step);
    }

    /** How a name is written in a list: with the mode after it when there is one. */
    static String label(final String name, final String suffix) {
        return suffix == null ? name : name + " " + suffix;
    }
}
