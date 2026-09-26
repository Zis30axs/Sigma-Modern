package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.ClientMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * SigmaModern's in-game HUD decorations: the ArrayList module's list of switched-on modules
 * ({@link ModernArrayList}), the TabGUI module's keyboard menu ({@link ModernTabGui}), a WASD keystroke display and the music "dynamic island" ({@link ModernIsland}), which
 * shows the music player's state and flashes module toggles made in game, and the suspect list's drawer
 * ({@link ModernSuspectDrawer}) while it is left out.
 *
 * <p>These are pure overlay chrome: they never write to module/setting state. Gameplay decorations draw with no
 * screen open; {@link #renderBrand} is a separate resident layer above in-game screens.</p>
 */
public final class ModernHud {
    /** First free row below the resident wordmark, including its outer spacing: where the left stack starts. */
    static final int BRAND_BOTTOM = 38;
    private static final int BRAND_X = 10, BRAND_Y = 10, BRAND_H = 22;
    private static final float BRAND_MARK = 12F, BRAND_ITALIC = 0.64F;
    private static final int STACK_GAP = 6;
    private static final long DEBUG_STALE_NANOS = 200_000_000L;

    // The left side, the old client's way (its EventRender2DOffset): below the brand, each element starts where the one
    // above it ended - TabGUI, then the keystrokes, then a top-left ArrayList. Reset every frame.
    private static int leftStack = BRAND_BOTTOM;
    // How far down F3's text reached on the left [0] and right [1], and when that was reported.
    private static final int[] debugBottom = new int[2];
    private static final long[] debugAt = new long[2];
    // Where the brand is drawn: eased between its corner and, while F3 is up, the top centre under the island.
    private static float brandX = -1F, brandY = -1F;
    private static long brandFrame;

    private ModernHud() {}

    public static boolean isActive() {
        return Client.getInstance().getClientModeManager().get() == ClientMode.SIGMA_MODERN;
    }

    /**
     * Called from a Sigma hook in {@code DebugScreenOverlay}: F3's text on one side of the screen reached {@code bottom}
     * this frame. F3 is drawn after the HUD, so the HUD reads the previous frame's - a frame late, which nothing shows.
     */
    public static void debugColumn(boolean left, int bottom) {
        int side = left ? 0 : 1;
        debugBottom[side] = bottom;
        debugAt[side] = System.nanoTime();
    }

    /** How far down F3's text reaches on that side, or 0 while it shows none there. */
    static int debugBottom(boolean left) {
        int side = left ? 0 : 1;
        return System.nanoTime() - debugAt[side] < DEBUG_STALE_NANOS ? debugBottom[side] : 0;
    }

    /**
     * Whether F3's text is up - as the old client did, the left stack (TabGUI, keystrokes) then hides, the ArrayList
     * starts below F3's right column and the brand moves to the top centre. Entries pinned to show without F3 count.
     */
    static boolean debugShowing() {
        return Minecraft.getInstance().debugEntries.isOverlayVisible() || debugBottom(true) > 0 || debugBottom(false) > 0;
    }

    /** Where the next element down the left side starts. */
    static int leftStack() {
        return leftStack;
    }

    /** Claims {@code height} of the left side for the element just drawn at {@link #leftStack()}. */
    static void stack(int height) {
        leftStack += height + STACK_GAP;
    }

    /**
     * Resident branding, extracted after screens so it survives menus and the F1 HUD toggle: the main menu's lockup -
     * the faceted Σ, "Sigma", and "Modern" in ice-blue italic - in miniature, on a slim pill of the dynamic island's
     * own glass (its default ice colours), so the two read as one set along the top of the screen and the mark stays
     * legible over a bright sky or snow. It sits in the top-left corner; while F3's text is up it slides to the top
     * centre, under the island, as the old client's watermark did.
     */
    public static void renderBrand(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !isActive()) return;

        float markX = 8F, markY = (BRAND_H - BRAND_MARK) / 2F;
        float wordX = markX + BRAND_MARK * 0.92F + 5F, wordY = BRAND_H / 2F - 5.6F;
        float italicX = wordX + ModernTypography.width(ModernTypography.Face.DISPLAY, Client.NAME, 1F) + 4.2F;
        int w = Math.round(italicX + ModernTypography.width(ModernTypography.Face.DISPLAY_ITALIC, "Modern", BRAND_ITALIC) + 10F);
        int radius = BRAND_H / 2;

        boolean aside = debugShowing();
        float targetX = aside ? (g.guiWidth() - w) / 2F : BRAND_X;
        float targetY = aside ? Math.max(BRAND_Y, ModernIsland.bottom() + 6) : BRAND_Y;
        long now = System.nanoTime();
        float dt = Math.min(0.05F, (now - brandFrame) / 1_000_000_000F);
        boolean snap = brandX < 0F || now - brandFrame > 250_000_000L;
        brandFrame = now;
        brandX = snap ? targetX : ModernStyle.smooth(brandX, targetX, dt, 12F);
        brandY = snap ? targetY : ModernStyle.smooth(brandY, targetY, dt, 12F);

        g.nextStratum();
        g.pose().pushMatrix();
        try {
            g.pose().translate(Math.round(brandX), Math.round(brandY));
            ModernStyle.dropShadow(g, 0, 2, w, BRAND_H, radius, 0.6F);
            ModernStyle.rounded(g, 0, 0, w, BRAND_H, radius, 0x80EDFAFF);
            ModernStyle.rounded(g, 1, 1, w - 2, BRAND_H - 2, radius - 1, 0x9C000000 | (ModernCoverColors.ICE_DEEP & 0xFFFFFF));
            // Light catching the top-left of the glass, as on the island, kept inside the pill.
            g.enableScissor(0, 0, w, BRAND_H);
            g.pose().pushMatrix();
            try {
                g.pose().translate(w * 0.26F, 2F);
                g.pose().scale(1F, 0.5F);
                ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, -30F, -30F, 60F, 0x24F5FEFF);
            } finally {
                g.pose().popMatrix();
                g.disableScissor();
            }

            float glow = BRAND_MARK * 2.4F;
            ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, markX + BRAND_MARK * 0.46F - glow / 2F, markY + BRAND_MARK / 2F - glow / 2F, glow, 0x40BFE8FF);
            ModernIcons.draw(g, ModernIcons.Icon.SIGMA, markX, markY, BRAND_MARK, 0xFFD6F1FF);
            ModernTypography.draw(g, ModernTypography.Face.DISPLAY, Client.NAME, wordX, wordY, 1F, 0xFFF2F8FC);
            // On the same baseline as "Sigma".
            ModernTypography.draw(g, ModernTypography.Face.DISPLAY_ITALIC, "Modern", italicX, wordY + 9F - 9F * BRAND_ITALIC, BRAND_ITALIC, 0xFFA8DDFA);
        } finally {
            g.pose().popMatrix();
        }
    }

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.player == null || !isActive()) return;
        leftStack = BRAND_BOTTOM;
        ModernTabGui.render(g);
        drawKeystrokes(g, mc.options);
        ModernArrayList.render(g);
        ModernIsland.render(g);
        ModernSuspectDrawer.renderHud(g);
    }

    /** The music visuals, under everything else on the HUD; called first thing in {@code Hud}'s pass. */
    public static void renderBackdrop(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.player == null || !isActive()) return;
        ModernMusicFx.render(g);
    }

    /** Reskins the vanilla hotbar strip as a glass cell row; called from {@code Hud.extractItemHotbar}. */
    public static void hotbarBackground(GuiGraphicsExtractor g, int screenCenter, int selectedSlot) {
        int w = 182, h = 22;
        int x = screenCenter - w / 2, y = g.guiHeight() - h;
        ModernStyle.darkGlass(g, x, y, w, h, 7, 0x611C2738);
        int cellW = w / 9;
        int cellX = x + selectedSlot * cellW + 1, cellY = y + 1, cellW2 = cellW - 2, cellH2 = h - 2;
        ModernStyle.halo(g, cellX, cellY, cellW2, cellH2, 4, ModernStyle.GLOW, 0.35F);
        ModernStyle.rounded(g, cellX, cellY, cellW2, cellH2, 4, 0x5CB6E9FF);
    }

    /**
     * WASD on the left side under the TabGUI, as the old client's KeyStrokes sat - clear of the chat in the bottom-left
     * corner - and hidden while F3's text is up.
     */
    private static void drawKeystrokes(GuiGraphicsExtractor g, Options options) {
        if (debugShowing()) return;
        int size = 20, gap = 3;
        int x = BRAND_X, y = leftStack();
        stack(size * 2 + gap);
        key(g, x + size + gap, y, size, "W", options.keyUp.isDown());
        key(g, x, y + size + gap, size, "A", options.keyLeft.isDown());
        key(g, x + size + gap, y + size + gap, size, "S", options.keyDown.isDown());
        key(g, x + (size + gap) * 2, y + size + gap, size, "D", options.keyRight.isDown());
    }

    private static void key(GuiGraphicsExtractor g, int x, int y, int size, String label, boolean pressed) {
        ModernStyle.darkGlass(g, x, y, size, size, 5, pressed ? 0xE6C7EBFF : 0x4D1C2A38);
        int color = pressed ? 0xFF204C63 : ModernStyle.MUTED;
        int w = ModernTypography.width(label);
        ModernTypography.draw(g, label, x + (size - w) / 2, y + size / 2 - 4, color, false);
    }

}
