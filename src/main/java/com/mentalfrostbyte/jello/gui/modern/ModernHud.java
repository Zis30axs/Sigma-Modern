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
    /** First free row below the resident wordmark, including its outer spacing. */
    static final int BRAND_BOTTOM = 38;
    private static final int BRAND_X = 10, BRAND_Y = 10, BRAND_H = 22;
    private static final float BRAND_MARK = 12F, BRAND_ITALIC = 0.64F;

    private ModernHud() {}

    public static boolean isActive() {
        return Client.getInstance().getClientModeManager().get() == ClientMode.SIGMA_MODERN;
    }

    /**
     * Resident branding, extracted after screens so it survives menus and the F1 HUD toggle: the main menu's lockup -
     * the faceted Σ, "Sigma", and "Modern" in ice-blue italic - in miniature, on a slim pill of the dynamic island's
     * own glass (its default ice colours), so the two read as one set along the top of the screen and the mark stays
     * legible over a bright sky or snow.
     */
    public static void renderBrand(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !isActive()) return;

        float markX = BRAND_X + 8F, markY = BRAND_Y + (BRAND_H - BRAND_MARK) / 2F;
        float wordX = markX + BRAND_MARK * 0.92F + 5F, wordY = BRAND_Y + BRAND_H / 2F - 5.6F;
        float italicX = wordX + ModernTypography.width(ModernTypography.Face.DISPLAY, Client.NAME, 1F) + 4.2F;
        int w = Math.round(italicX + ModernTypography.width(ModernTypography.Face.DISPLAY_ITALIC, "Modern", BRAND_ITALIC) + 10F) - BRAND_X;
        int radius = BRAND_H / 2;
        g.nextStratum();
        ModernStyle.dropShadow(g, BRAND_X, BRAND_Y + 2, w, BRAND_H, radius, 0.6F);
        ModernStyle.rounded(g, BRAND_X, BRAND_Y, w, BRAND_H, radius, 0x80EDFAFF);
        ModernStyle.rounded(g, BRAND_X + 1, BRAND_Y + 1, w - 2, BRAND_H - 2, radius - 1, 0x9C000000 | (ModernCoverColors.ICE_DEEP & 0xFFFFFF));
        // Light catching the top-left of the glass, as on the island, kept inside the pill.
        g.enableScissor(BRAND_X, BRAND_Y, BRAND_X + w, BRAND_Y + BRAND_H);
        g.pose().pushMatrix();
        try {
            g.pose().translate(BRAND_X + w * 0.26F, BRAND_Y + 2F);
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
    }

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.player == null || !isActive()) return;
        ModernArrayList.render(g);
        ModernTabGui.render(g);
        drawKeystrokes(g, mc.options);
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

    private static void drawKeystrokes(GuiGraphicsExtractor g, Options options) {
        int size = 20, gap = 3;
        int x = 8, y = g.guiHeight() - size * 2 - gap - 8;
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
