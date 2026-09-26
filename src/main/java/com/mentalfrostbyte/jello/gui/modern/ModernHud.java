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
    static final int BRAND_BOTTOM = 54;

    private ModernHud() {}

    public static boolean isActive() {
        return Client.getInstance().getClientModeManager().get() == ClientMode.SIGMA_MODERN;
    }

    /** Resident branding, extracted after screens so it survives menus and the F1 HUD toggle. */
    public static void renderBrand(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !isActive()) return;

        int x = 10, y = 10, h = 36;
        float scale = 1.8F;
        int w = Math.max(82, (int)Math.ceil(ModernTypography.width(ModernTypography.Face.DISPLAY, Client.NAME, scale)) + 24);
        g.nextStratum();
        ModernStyle.darkGlass(g, x, y, w, h, 8, 0xC01C2A38);
        ModernStyle.rounded(g, x + 1, y + 9, 2, h - 18, 1, ModernStyle.GLOW);
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY, Client.NAME, x + 12F, y + 2F, scale, ModernStyle.TEXT);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, "M O D E R N", x + 13F, y + 23F, 0.65F, ModernStyle.ACCENT);
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
