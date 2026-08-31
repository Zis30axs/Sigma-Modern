package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.util.client.render.theme.ClientColors;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import com.mentalfrostbyte.jello.util.math.SmoothInterpolator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Source-native 26.2 presentation of Sigma's Jello main menu.
 *
 * <p>The layout and motion intentionally follow the old client: a centred Sigma mark, five 128px menu
 * slots on a 122px stride, simple top text actions, light-grey-blue tinting and a soft hover shadow.
 * The old fixed-function OpenGL implementation is not carried forward.</p>
 */
public final class JelloMainMenuScreen extends SigmaMainMenuScreen {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/sigma/back.png");
    private static final Identifier LOGO = Identifier.withDefaultNamespace("textures/gui/sigma/logo.png");

    private static final int LIGHT = ClientColors.LIGHT_GREYISH_BLUE.getColor();
    private static final int DEEP_TEAL = ClientColors.DEEP_TEAL.getColor();
    private static final int TEXT_DIM = withAlpha(LIGHT, 178);
    private static final String[] ACTIONS = {"Singleplayer", "Multiplayer", "Realms", "Options", "Language"};

    private final Animation[] actionHover = new Animation[ACTIONS.length];
    private final Animation exitHover = new Animation(160, 140, Animation.Direction.BACKWARDS);
    private final Animation switchHover = new Animation(160, 140, Animation.Direction.BACKWARDS);

    public JelloMainMenuScreen() {
        super(Component.literal("Sigma Jello"));
        for (int i = 0; i < this.actionHover.length; i++) {
            this.actionHover[i] = new Animation(160, 140, Animation.Direction.BACKWARDS);
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, this.height, 1280, 720, 1280, 720);
        graphics.fill(0, 0, this.width, this.height, withAlpha(DEEP_TEAL, 48));

        int logoWidth = Math.min(455, Math.max(180, this.width - 40));
        int logoHeight = logoWidth * 78 / 455;
        int logoX = (this.width - logoWidth) / 2;
        int logoY = Math.max(28, this.height / 2 - logoHeight);
        graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, logoX, logoY, 0.0F, 0.0F,
            logoWidth, logoHeight, 910, 156, 910, 156, LIGHT);

        MenuLayout layout = this.menuLayout();
        for (int i = 0; i < ACTIONS.length; i++) {
            int x = layout.startX + i * layout.stride;
            boolean hovered = inside(mouseX, mouseY, x, layout.y, layout.size, layout.size);
            Animation animation = this.actionHover[i];
            animation.changeDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);

            float progress = animation.calcPercent();
            float motion = animation.getDirection() == Animation.Direction.FORWARDS
                ? SmoothInterpolator.interpolate(progress, 0.24, 0.88, 0.30, 1.00)
                : SmoothInterpolator.interpolate(progress, 0.45, 0.02, 0.59, 0.28);

            int grow = Math.round(layout.size * motion * 0.10F);
            int lift = Math.round(layout.size * motion * 0.10F);
            int drawX = x - grow;
            int drawY = layout.y - grow - lift;
            int drawSize = layout.size + grow * 2;

            if (progress > 0.001F) {
                GuiVisuals.softGlow(graphics, drawX, drawY, drawSize, drawSize, LIGHT, Math.max(10, layout.size * 2 / 3), progress * 0.22F);
            }

            // The original slot is icon-driven. Until the original binary icon set is brought across,
            // keep the slot visually neutral rather than inventing a replacement card or border.
            String label = ACTIONS[i];
            int textX = drawX + (drawSize - this.font.width(label)) / 2;
            int textY = drawY + drawSize - Math.max(14, drawSize / 5);
            graphics.text(this.font, label, textX + 1, textY + 1, withAlpha(DEEP_TEAL, 128), false);
            graphics.text(this.font, label, textX, textY, LIGHT, false);
        }

        this.drawTopAction(graphics, "Exit", 30, mouseX, mouseY, this.exitHover, 0.40F);
        this.drawTopAction(graphics, "Switch", 90, mouseX, mouseY, this.switchHover, 0.70F);

        String version = "Jello for Sigma " + Client.FULL_VERSION + "  -  Minecraft " + this.minecraft.getLaunchedVersion();
        graphics.text(this.font, "© Sigma Prod", 10, this.height - 16, LIGHT, true);
        graphics.text(this.font, version, this.width - this.font.width(version) - 9, this.height - 16, LIGHT, true);
    }

    private void drawTopAction(
        final GuiGraphicsExtractor graphics,
        final String text,
        final int x,
        final int mouseX,
        final int mouseY,
        final Animation animation,
        final float baseAlpha
    ) {
        int width = this.font.width(text);
        boolean hovered = inside(mouseX, mouseY, x, 20, width + 8, 20);
        animation.changeDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        float progress = animation.calcPercent();
        float alpha = Math.min(1.0F, baseAlpha + progress * (1.0F - baseAlpha));
        graphics.text(this.font, text, x, 24 - Math.round(progress), withAlpha(LIGHT, Math.round(255.0F * alpha)), false);
    }

    private MenuLayout menuLayout() {
        int size = Math.min(128, Math.max(64, (this.width - 36) / 5));
        int overlap = Math.max(3, Math.round(size * 6.0F / 128.0F));
        int stride = size - overlap;
        int totalWidth = size + stride * (ACTIONS.length - 1);
        int startX = (this.width - totalWidth) / 2;
        int y = this.height / 2 + 14;
        y = Math.min(y, this.height - size - 36);
        y = Math.max(80, y);
        return new MenuLayout(size, stride, startX, y);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (inside(mouseX, mouseY, 26, 18, 54, 24)) {
            this.quitGame();
            return true;
        }
        if (inside(mouseX, mouseY, 86, 18, 70, 24)) {
            this.openModeSelect();
            return true;
        }

        MenuLayout layout = this.menuLayout();
        for (int i = 0; i < ACTIONS.length; i++) {
            int x = layout.startX + i * layout.stride;
            if (!inside(mouseX, mouseY, x, layout.y, layout.size, layout.size)) {
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

    private static int withAlpha(final int color, final int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | color & 0x00FFFFFF;
    }

    private record MenuLayout(int size, int stride, int startX, int y) {
    }
}
