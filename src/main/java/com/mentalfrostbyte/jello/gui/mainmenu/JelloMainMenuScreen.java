package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Modern 26.2 implementation of Sigma's Jello main menu.
 *
 * <p>The old menu's identity was a large Sigma mark over a soft blue scene with a row of large actions,
 * while mode switching lived in the main menu itself. This keeps that structure without dragging the
 * old fixed-function OpenGL GUI framework into the modern renderer.</p>
 */
public final class JelloMainMenuScreen extends SigmaMainMenuScreen {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/sigma/back.png");
    private static final Identifier LOGO = Identifier.withDefaultNamespace("textures/gui/sigma/logo.png");

    private static final int TEXT = 0xFFF1FAFF;
    private static final int TEXT_DIM = 0xFFA9C7D6;
    private static final int CARD = 0x9A0B2735;
    private static final int CARD_HOVER = 0xD21C5369;
    private static final int BORDER = 0x8066D9FF;

    private static final String[] ACTIONS = {"Singleplayer", "Multiplayer", "Realms", "Options", "Language"};

    public JelloMainMenuScreen() {
        super(Component.literal("Sigma Jello"));
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, this.height, 1280, 720, 1280, 720);
        graphics.fill(0, 0, this.width, this.height, 0x52001018);

        int logoWidth = Math.min(420, Math.max(180, this.width - 80));
        int logoHeight = logoWidth * 156 / 910;
        int logoX = (this.width - logoWidth) / 2;
        int logoY = Math.max(24, this.height / 8);
        graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, logoX, logoY, 0.0F, 0.0F, logoWidth, logoHeight, 910, 156, 910, 156);

        int gap = 10;
        int available = Math.max(250, this.width - 40);
        int cardWidth = Math.min(116, (available - gap * (ACTIONS.length - 1)) / ACTIONS.length);
        int cardHeight = Math.max(72, Math.min(112, this.height / 4));
        int totalWidth = cardWidth * ACTIONS.length + gap * (ACTIONS.length - 1);
        int startX = (this.width - totalWidth) / 2;
        int cardY = Math.min(this.height - cardHeight - 70, logoY + logoHeight + Math.max(36, this.height / 10));

        for (int i = 0; i < ACTIONS.length; i++) {
            int x = startX + i * (cardWidth + gap);
            boolean hovered = inside(mouseX, mouseY, x, cardY, cardWidth, cardHeight);
            graphics.fill(x, cardY, x + cardWidth, cardY + cardHeight, hovered ? CARD_HOVER : CARD);
            graphics.outline(x, cardY, cardWidth, cardHeight, hovered ? 0xFF66D9FF : BORDER);
            String label = ACTIONS[i];
            graphics.text(this.font, label, x + (cardWidth - this.font.width(label)) / 2, cardY + cardHeight / 2 - 4, TEXT);
        }

        this.drawTopAction(graphics, "Exit", 22, mouseX, mouseY);
        this.drawTopAction(graphics, "Switch", 78, mouseX, mouseY);

        String version = "Sigma " + Client.FULL_VERSION + "  •  Jello";
        graphics.text(this.font, version, this.width - this.font.width(version) - 10, this.height - 16, TEXT_DIM);
        graphics.text(this.font, "© Sigma Prod", 10, this.height - 16, TEXT_DIM);
    }

    private void drawTopAction(final GuiGraphicsExtractor graphics, final String text, final int x, final int mouseX, final int mouseY) {
        boolean hovered = inside(mouseX, mouseY, x - 6, 12, this.font.width(text) + 12, 18);
        if (hovered) {
            graphics.fill(x - 6, 12, x + this.font.width(text) + 6, 30, 0x5533B5D6);
        }
        graphics.text(this.font, text, x, 17, hovered ? TEXT : TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (inside(mouseX, mouseY, 16, 12, 50, 18)) {
            this.quitGame();
            return true;
        }
        if (inside(mouseX, mouseY, 72, 12, 62, 18)) {
            this.openModeSelect();
            return true;
        }

        int gap = 10;
        int available = Math.max(250, this.width - 40);
        int cardWidth = Math.min(116, (available - gap * (ACTIONS.length - 1)) / ACTIONS.length);
        int cardHeight = Math.max(72, Math.min(112, this.height / 4));
        int totalWidth = cardWidth * ACTIONS.length + gap * (ACTIONS.length - 1);
        int startX = (this.width - totalWidth) / 2;
        int logoWidth = Math.min(420, Math.max(180, this.width - 80));
        int logoHeight = logoWidth * 156 / 910;
        int logoY = Math.max(24, this.height / 8);
        int cardY = Math.min(this.height - cardHeight - 70, logoY + logoHeight + Math.max(36, this.height / 10));

        for (int i = 0; i < ACTIONS.length; i++) {
            int x = startX + i * (cardWidth + gap);
            if (!inside(mouseX, mouseY, x, cardY, cardWidth, cardHeight)) {
                continue;
            }

            switch (i) {
                case 0 -> this.openSingleplayer();
                case 1 -> this.openMultiplayer();
                case 2 -> this.openRealms();
                case 3 -> this.openOptions();
                case 4 -> this.openLanguage();
                default -> throw new IllegalStateException("Unexpected Jello menu action " + i);
            }
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }
}
