package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Modern source-native counterpart of Sigma Classic's boxed main menu. */
public final class ClassicMainMenuScreen extends SigmaMainMenuScreen {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/sigma/back.png");
    private static final Identifier CLASSIC_MARK = Identifier.withDefaultNamespace("textures/gui/sigma/classic.png");

    private static final int TEXT = 0xFFE7EDF1;
    private static final int TEXT_DIM = 0xFF9DA8AE;
    private static final int PANEL = 0xC2171B1E;
    private static final int PANEL_HOVER = 0xE22B3439;
    private static final int BORDER = 0xFF626C72;

    private static final String[] ACTIONS = {"Singleplayer", "Multiplayer", "Options", "Language", "Switch", "Exit"};

    public ClassicMainMenuScreen() {
        super(Component.literal("Sigma Classic"));
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, this.height, 1280, 720, 1280, 720);
        graphics.fill(0, 0, this.width, this.height, 0xA20B0D0E);

        int markWidth = Math.min(264, Math.max(160, this.width - 80));
        int markHeight = markWidth * 61 / 264;
        int markX = (this.width - markWidth) / 2;
        int markY = 24;
        graphics.blit(RenderPipelines.GUI_TEXTURED, CLASSIC_MARK, markX, markY, 0.0F, 0.0F, markWidth, markHeight, 264, 61, 264, 61);

        int cardWidth = Math.min(150, Math.max(88, (this.width - 80) / 3 - 10));
        int cardHeight = Math.min(82, Math.max(58, (this.height - 170) / 2 - 8));
        int gap = 10;
        int totalWidth = cardWidth * 3 + gap * 2;
        int startX = (this.width - totalWidth) / 2;
        int startY = Math.max(100, markY + markHeight + 26);

        for (int i = 0; i < ACTIONS.length; i++) {
            int col = i % 3;
            int row = i / 3;
            int x = startX + col * (cardWidth + gap);
            int y = startY + row * (cardHeight + gap);
            boolean hovered = inside(mouseX, mouseY, x, y, cardWidth, cardHeight);
            graphics.fill(x, y, x + cardWidth, y + cardHeight, hovered ? PANEL_HOVER : PANEL);
            graphics.outline(x, y, cardWidth, cardHeight, hovered ? 0xFFD1D8DC : BORDER);
            String label = ACTIONS[i];
            graphics.text(this.font, label, x + (cardWidth - this.font.width(label)) / 2, y + cardHeight / 2 - 4, hovered ? 0xFFFFFFFF : TEXT);
        }

        String hello = "Hello, " + this.minecraft.getUser().getName();
        graphics.text(this.font, hello, 10, this.height - 28, TEXT_DIM);
        graphics.text(this.font, "You are using Sigma " + Client.FULL_VERSION, 10, this.height - 16, TEXT_DIM);
        String version = "Classic • Minecraft " + this.minecraft.getLaunchedVersion();
        graphics.text(this.font, version, this.width - this.font.width(version) - 10, this.height - 16, TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        int cardWidth = Math.min(150, Math.max(88, (this.width - 80) / 3 - 10));
        int cardHeight = Math.min(82, Math.max(58, (this.height - 170) / 2 - 8));
        int gap = 10;
        int totalWidth = cardWidth * 3 + gap * 2;
        int startX = (this.width - totalWidth) / 2;
        int markWidth = Math.min(264, Math.max(160, this.width - 80));
        int markHeight = markWidth * 61 / 264;
        int startY = Math.max(100, 24 + markHeight + 26);
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        for (int i = 0; i < ACTIONS.length; i++) {
            int x = startX + (i % 3) * (cardWidth + gap);
            int y = startY + (i / 3) * (cardHeight + gap);
            if (!inside(mouseX, mouseY, x, y, cardWidth, cardHeight)) {
                continue;
            }

            switch (i) {
                case 0 -> this.openSingleplayer();
                case 1 -> this.openMultiplayer();
                case 2 -> this.openOptions();
                case 3 -> this.openLanguage();
                case 4 -> this.openModeSelect();
                case 5 -> this.quitGame();
                default -> throw new IllegalStateException("Unexpected Classic menu action " + i);
            }
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }
}
