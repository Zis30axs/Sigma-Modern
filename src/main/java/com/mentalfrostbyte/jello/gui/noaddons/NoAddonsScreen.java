package com.mentalfrostbyte.jello.gui.noaddons;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.ModeSelectScreen;
import com.mentalfrostbyte.jello.gui.SigmaClickGui;
import com.mentalfrostbyte.jello.gui.click.ClickGuiHandler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * NoAddons presentation fallback.
 *
 * <p>This is intentionally not a module browser. It only provides a minimal way to leave NoAddons mode
 * and switch back to a Sigma presentation. It never touches module registration or module enabled state.</p>
 */
public final class NoAddonsScreen extends Screen implements SigmaClickGui {

    private static final int BG = 0xB0101010;
    private static final int PANEL = 0xD0181818;
    private static final int BORDER = 0xFF555555;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xFFA0A0A0;

    public NoAddonsScreen() {
        super(Component.literal("No Addons"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        graphics.fill(0, 0, this.width, this.height, BG);
        graphics.text(this.font, "No Addons - Sigma", this.width / 2 - this.font.width("No Addons - Sigma") / 2, 40, TEXT);
        graphics.text(this.font, "This mode does not add a module GUI.", this.width / 2 - this.font.width("This mode does not add a module GUI.") / 2, 60, TEXT_DIM);

        int buttonX = this.width / 2 - 80;
        int buttonY = 100;
        int buttonW = 160;
        int buttonH = 20;
        boolean hovered = mouseX >= buttonX && mouseX < buttonX + buttonW && mouseY >= buttonY && mouseY < buttonY + buttonH;
        graphics.fill(buttonX, buttonY, buttonX + buttonW, buttonY + buttonH, hovered ? 0xFF4A4A6A : PANEL);
        graphics.outline(buttonX, buttonY, buttonW, buttonH, BORDER);
        graphics.text(this.font, "Switch Mode", buttonX + buttonW / 2 - this.font.width("Switch Mode") / 2, buttonY + 6, TEXT);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        int x = (int) event.x();
        int y = (int) event.y();
        int buttonX = this.width / 2 - 80;
        int buttonY = 100;
        if (event.button() == 0 && x >= buttonX && x < buttonX + 160 && y >= buttonY && y < buttonY + 20) {
            this.minecraft.gui.setScreen(new ModeSelectScreen(this));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (InputConstants.getKey(event).equals(ClickGuiHandler.OPEN_KEY)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        ClickGuiHandler.close();
    }

}
