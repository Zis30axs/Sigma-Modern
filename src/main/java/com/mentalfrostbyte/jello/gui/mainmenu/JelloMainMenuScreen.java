package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.util.client.render.LegacyUiScale;
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
 * <p>Historical coordinates remain expressed in old Sigma framebuffer pixels and are converted through
 * {@link LegacyUiScale}; this prevents the classic 2x/3x enlargement when Minecraft's GUI scale is above 1.</p>
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

        int logoWidth = Math.min(LegacyUiScale.size(455), Math.max(1, this.width - LegacyUiScale.size(40)));
        int logoHeight = Math.max(1, logoWidth * 78 / 455);
        int logoX = (this.width - logoWidth) / 2;
        int logoY = Math.max(LegacyUiScale.px(28), this.height / 2 - logoHeight);
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
                GuiVisuals.softGlow(graphics, drawX, drawY, drawSize, drawSize, LIGHT,
                    Math.max(LegacyUiScale.size(10), layout.size * 2 / 3), progress * 0.22F);
            }

            String label = ACTIONS[i];
            int textX = drawX + (drawSize - this.font.width(label)) / 2;
            int textY = drawY + drawSize - Math.max(LegacyUiScale.size(14), drawSize / 5);
            graphics.text(this.font, label, textX + 1, textY + 1, withAlpha(DEEP_TEAL, 128), false);
            graphics.text(this.font, label, textX, textY, LIGHT, false);
        }

        this.drawTopAction(graphics, "Exit", 30, mouseX, mouseY, this.exitHover, 0.40F);
        this.drawTopAction(graphics, "Switch", 90, mouseX, mouseY, this.switchHover, 0.70F);

        String version = "Jello for Sigma " + Client.FULL_VERSION + "  -  Minecraft " + this.minecraft.getLaunchedVersion();
        graphics.text(this.font, "© Sigma Prod", LegacyUiScale.px(10), this.height - LegacyUiScale.size(16), LIGHT, true);
        graphics.text(this.font, version, this.width - this.font.width(version) - LegacyUiScale.size(9), this.height - LegacyUiScale.size(16), TEXT_DIM, true);
    }

    private void drawTopAction(
        final GuiGraphicsExtractor graphics,
        final String text,
        final int legacyX,
        final int mouseX,
        final int mouseY,
        final Animation animation,
        final float baseAlpha
    ) {
        int x = LegacyUiScale.px(legacyX);
        int top = LegacyUiScale.px(20);
        int width = this.font.width(text);
        boolean hovered = inside(mouseX, mouseY, x, top, width + LegacyUiScale.size(8), LegacyUiScale.size(20));
        animation.changeDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        float progress = animation.calcPercent();
        float alpha = Math.min(1.0F, baseAlpha + progress * (1.0F - baseAlpha));
        graphics.text(this.font, text, x, LegacyUiScale.px(24) - Math.round(LegacyUiScale.px(1.0F) * progress),
            withAlpha(LIGHT, Math.round(255.0F * alpha)), false);
    }

    private MenuLayout menuLayout() {
        int size = LegacyUiScale.size(128);
        int stride = LegacyUiScale.size(122);
        int totalWidth = size + stride * (ACTIONS.length - 1);
        int startX = (this.width - totalWidth) / 2;
        int y = this.height / 2 + LegacyUiScale.px(14);
        y = Math.min(y, this.height - size - LegacyUiScale.size(36));
        y = Math.max(LegacyUiScale.size(80), y);
        return new MenuLayout(size, stride, startX, y);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (inside(mouseX, mouseY, LegacyUiScale.px(26), LegacyUiScale.px(18), LegacyUiScale.size(54), LegacyUiScale.size(24))) {
            this.quitGame();
            return true;
        }
        if (inside(mouseX, mouseY, LegacyUiScale.px(86), LegacyUiScale.px(18), LegacyUiScale.size(70), LegacyUiScale.size(24))) {
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
