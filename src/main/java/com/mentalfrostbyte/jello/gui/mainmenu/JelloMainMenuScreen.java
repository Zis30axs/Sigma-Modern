package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import com.mentalfrostbyte.jello.util.math.Easing;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Modern 26.2 Jello main menu with backend-neutral blur, bloom-like glow and reversible hover motion. */
public final class JelloMainMenuScreen extends SigmaMainMenuScreen {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/sigma/back.png");
    private static final Identifier LOGO = Identifier.withDefaultNamespace("textures/gui/sigma/logo.png");
    private static final int TEXT = 0xFFF1FAFF;
    private static final int TEXT_DIM = 0xFFA9C7D6;
    private static final int CARD_TOP = 0xA8143543;
    private static final int CARD_BOTTOM = 0xB809202B;
    private static final int CARD_HOVER_TOP = 0xDC276A82;
    private static final int CARD_HOVER_BOTTOM = 0xD7194A60;
    private static final int BORDER = 0x8066D9FF;
    private static final int ACCENT = 0x0066D9FF;
    private static final String[] ACTIONS = {"Singleplayer", "Multiplayer", "Realms", "Options", "Language"};

    private final Animation[] actionHover = new Animation[ACTIONS.length];
    private final Animation exitHover = new Animation(160, 140, Animation.Direction.BACKWARDS);
    private final Animation switchHover = new Animation(160, 140, Animation.Direction.BACKWARDS);

    public JelloMainMenuScreen() {
        super(Component.literal("Sigma Jello"));
        for (int i = 0; i < this.actionHover.length; i++) {
            this.actionHover[i] = new Animation(180, 145, Animation.Direction.BACKWARDS);
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, this.height, 1280, 720, 1280, 720);
        graphics.fill(0, 0, this.width, this.height, 0x2E001018);
        GuiVisuals.blurBackground(graphics);
        graphics.fillGradient(0, 0, this.width, this.height, 0x300A2733, 0x6B020A0E);

        int logoWidth = Math.min(420, Math.max(180, this.width - 80));
        int logoHeight = logoWidth * 156 / 910;
        int logoX = (this.width - logoWidth) / 2;
        int logoY = Math.max(24, this.height / 8);
        GuiVisuals.softGlow(graphics, logoX + logoWidth / 8, logoY + logoHeight / 4, logoWidth * 3 / 4, Math.max(1, logoHeight / 2), ACCENT, 18, 0.10F);
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
            Animation animation = this.actionHover[i];
            animation.changeDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
            float hover = Easing.easeOutCubic(animation.calcPercent(), 0.0F, 1.0F, 1.0F);
            int grow = Math.round(3.0F * hover);
            int lift = Math.round(4.0F * hover);
            int drawX = x - grow;
            int drawY = cardY - lift - grow;
            int drawWidth = cardWidth + grow * 2;
            int drawHeight = cardHeight + grow * 2;

            GuiVisuals.softShadow(graphics, drawX, drawY + 3, drawWidth, drawHeight, 14, 0.62F + hover * 0.20F);
            if (hover > 0.01F) {
                GuiVisuals.softGlow(graphics, drawX, drawY, drawWidth, drawHeight, ACCENT, 15, 0.08F + hover * 0.18F);
            }
            graphics.fillGradient(drawX, drawY, drawX + drawWidth, drawY + drawHeight,
                hover > 0.01F ? CARD_HOVER_TOP : CARD_TOP, hover > 0.01F ? CARD_HOVER_BOTTOM : CARD_BOTTOM);
            graphics.outline(drawX, drawY, drawWidth, drawHeight, hover > 0.01F ? 0xFF8BE7FF : BORDER);
            String label = ACTIONS[i];
            graphics.text(this.font, label, drawX + (drawWidth - this.font.width(label)) / 2, drawY + drawHeight / 2 - 4, TEXT);
        }

        this.drawTopAction(graphics, "Exit", 22, mouseX, mouseY, this.exitHover);
        this.drawTopAction(graphics, "Switch", 78, mouseX, mouseY, this.switchHover);
        String version = "Sigma " + Client.FULL_VERSION + "  •  Jello";
        graphics.text(this.font, version, this.width - this.font.width(version) - 10, this.height - 16, TEXT_DIM);
        graphics.text(this.font, "© Sigma Prod", 10, this.height - 16, TEXT_DIM);
    }

    private void drawTopAction(final GuiGraphicsExtractor graphics, final String text, final int x, final int mouseX, final int mouseY, final Animation animation) {
        int width = this.font.width(text) + 12;
        boolean hovered = inside(mouseX, mouseY, x - 6, 12, width, 18);
        animation.changeDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        float hover = Easing.easeOutCubic(animation.calcPercent(), 0.0F, 1.0F, 1.0F);
        if (hover > 0.01F) {
            GuiVisuals.softGlow(graphics, x - 6, 12, width, 18, ACCENT, 9, 0.10F * hover);
            graphics.fill(x - 6, 12, x - 6 + width, 30, alpha(0x33B5D6, Math.round(72.0F * hover)));
        }
        graphics.text(this.font, text, x, 17 - Math.round(hover), hover > 0.05F ? TEXT : TEXT_DIM);
    }

    private static int alpha(final int rgb, final int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | rgb & 0x00FFFFFF;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (inside(mouseX, mouseY, 16, 12, 50, 18)) { this.quitGame(); return true; }
        if (inside(mouseX, mouseY, 72, 12, 62, 18)) { this.openModeSelect(); return true; }

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
            if (!inside(mouseX, mouseY, x, cardY, cardWidth, cardHeight)) continue;
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
