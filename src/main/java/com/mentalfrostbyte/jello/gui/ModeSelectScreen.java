package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.mainmenu.MainMenuRouter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Main-menu mode selector, matching Sigma's original SwitchScreen flow.
 *
 * <p>It is not a ClickGUI and does not change presentation from inside gameplay. A successful selection
 * is persisted and immediately opens the selected main menu. When opened by a main menu, Escape returns
 * to that menu; on first run a choice is required.</p>
 */
public final class ModeSelectScreen extends Screen {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/sigma/back.png");
    private static final Identifier LOGO = Identifier.withDefaultNamespace("textures/gui/sigma/logo.png");
    private static final Identifier NO_ADDONS_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/noaddons.png");
    private static final Identifier CLASSIC_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/classic.png");
    private static final Identifier JELLO_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/jello.png");
    private static final Identifier YOUTUBE_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/youtube.png");
    private static final Identifier REDDIT_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/reddit.png");
    private static final Identifier GUILDED_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/guilded.png");

    private static final int NO_ADDONS_WIDTH = 537;
    private static final int NO_ADDONS_HEIGHT = 93;
    private static final int SMALL_WIDTH = 264;
    private static final int SMALL_HEIGHT = 61;
    private static final int GAP = 9;
    private static final int LOGO_WIDTH = 455;
    private static final int LOGO_HEIGHT = 78;

    private static final int HOVER_BORDER = 0xFF66D9FF;
    private static final int BORDER = 0xFF3A3A3A;

    private final @Nullable Screen returnScreen;
    private final boolean selectionRequired;

    public ModeSelectScreen(final @Nullable Screen returnScreen) {
        this(returnScreen, false);
    }

    public ModeSelectScreen(final @Nullable Screen returnScreen, final boolean selectionRequired) {
        super(Component.literal("Select Client Mode"));
        this.returnScreen = returnScreen;
        this.selectionRequired = selectionRequired;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, this.height, 1280, 720, 1280, 720);
        graphics.fill(0, 0, this.width, this.height, 0x66000000);

        int logoWidth = Math.min(LOGO_WIDTH, this.width - 40);
        int logoHeight = logoWidth * LOGO_HEIGHT / LOGO_WIDTH;
        int logoX = (this.width - logoWidth) / 2;
        int logoY = 24;
        graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, logoX, logoY, 0.0F, 0.0F, logoWidth, logoHeight, 910, 156, 910, 156);

        float scale = Math.min(1.0F, (this.width - 24) / (float) NO_ADDONS_WIDTH);
        int bigWidth = (int) (NO_ADDONS_WIDTH * scale);
        int bigHeight = (int) (NO_ADDONS_HEIGHT * scale);
        int smallWidth = (int) (SMALL_WIDTH * scale);
        int smallHeight = (int) (SMALL_HEIGHT * scale);
        int gap = Math.max(4, (int) (GAP * scale));

        int cardsTop = logoY + logoHeight + 28;
        int cardsTotalHeight = bigHeight + gap + smallHeight;
        int y = cardsTop + Math.max(0, (this.height - cardsTop - cardsTotalHeight - 40) / 2);
        int x = (this.width - bigWidth) / 2;

        this.drawModeCard(graphics, NO_ADDONS_IMAGE, ClientMode.NO_ADDONS, x, y, bigWidth, bigHeight,
                NO_ADDONS_WIDTH, NO_ADDONS_HEIGHT, mouseX, mouseY);
        this.drawModeCard(graphics, CLASSIC_IMAGE, ClientMode.CLASSIC, x, y + bigHeight + gap, smallWidth, smallHeight,
                SMALL_WIDTH, SMALL_HEIGHT, mouseX, mouseY);
        this.drawModeCard(graphics, JELLO_IMAGE, ClientMode.JELLO, x + smallWidth + gap, y + bigHeight + gap,
                smallWidth, smallHeight, SMALL_WIDTH, SMALL_HEIGHT, mouseX, mouseY);

        this.drawSocialButtons(graphics);
    }

    private void drawModeCard(final GuiGraphicsExtractor graphics, final Identifier texture, final ClientMode mode,
                              final int x, final int y, final int width, final int height,
                              final int textureWidth, final int textureHeight,
                              final int mouseX, final int mouseY) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, width, height, textureWidth, textureHeight, textureWidth, textureHeight);
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        boolean selected = !this.selectionRequired && Client.getInstance().getClientModeManager().get() == mode;
        if (hovered || selected) {
            graphics.outline(x, y, width, height, hovered ? HOVER_BORDER : BORDER);
        }
    }

    private void drawSocialButtons(final GuiGraphicsExtractor graphics) {
        int totalWidth = 174;
        int x = (this.width - totalWidth) / 2;
        int y = this.height - 44;
        if (y < 0 || x < 0) {
            return;
        }

        graphics.blit(RenderPipelines.GUI_TEXTURED, YOUTUBE_IMAGE, x, y, 0.0F, 0.0F, 65, 34, 65, 34, 65, 34);
        graphics.blit(RenderPipelines.GUI_TEXTURED, REDDIT_IMAGE, x + 85, y, 0.0F, 0.0F, 36, 34, 36, 34, 36, 34);
        graphics.blit(RenderPipelines.GUI_TEXTURED, GUILDED_IMAGE, x + 142, y, 0.0F, 0.0F, 32, 34, 32, 34, 32, 34);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        float scale = Math.min(1.0F, (this.width - 24) / (float) NO_ADDONS_WIDTH);
        int bigWidth = (int) (NO_ADDONS_WIDTH * scale);
        int bigHeight = (int) (NO_ADDONS_HEIGHT * scale);
        int smallWidth = (int) (SMALL_WIDTH * scale);
        int smallHeight = (int) (SMALL_HEIGHT * scale);
        int gap = Math.max(4, (int) (GAP * scale));

        int logoWidth = Math.min(LOGO_WIDTH, this.width - 40);
        int logoHeight = logoWidth * LOGO_HEIGHT / LOGO_WIDTH;
        int logoY = 24;
        int cardsTop = logoY + logoHeight + 28;
        int cardsTotalHeight = bigHeight + gap + smallHeight;
        int y = cardsTop + Math.max(0, (this.height - cardsTop - cardsTotalHeight - 40) / 2);
        int x = (this.width - bigWidth) / 2;

        ClientMode selected = null;
        if (inside(mouseX, mouseY, x, y, bigWidth, bigHeight)) {
            selected = ClientMode.NO_ADDONS;
        } else if (inside(mouseX, mouseY, x, y + bigHeight + gap, smallWidth, smallHeight)) {
            selected = ClientMode.CLASSIC;
        } else if (inside(mouseX, mouseY, x + smallWidth + gap, y + bigHeight + gap, smallWidth, smallHeight)) {
            selected = ClientMode.JELLO;
        }

        if (selected == null) {
            return super.mouseClicked(event, doubleClick);
        }

        Client.getInstance().getClientModeManager().set(selected);
        Client.getInstance().saveConfig();
        MainMenuRouter.openSelected();
        return true;
    }

    private static boolean inside(final int x, final int y, final int left, final int top, final int width, final int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.isEscape()) {
            if (!this.selectionRequired) {
                this.onClose();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (this.selectionRequired) {
            return;
        }
        if (this.returnScreen != null) {
            this.minecraft.gui.setScreen(this.returnScreen);
        } else {
            MainMenuRouter.openSelected();
        }
    }
}
