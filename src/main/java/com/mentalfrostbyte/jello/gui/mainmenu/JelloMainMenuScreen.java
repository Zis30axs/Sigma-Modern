package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.util.client.render.LegacyUiScale;
import com.mentalfrostbyte.jello.util.client.render.theme.ClientColors;
import com.mentalfrostbyte.jello.util.math.SmoothInterpolator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Source-native 26.2 presentation of Sigma's original Jello main menu.
 *
 * <p>The artwork, button order, historical framebuffer-pixel measurements and hover motion come from
 * the old client. Rendering itself stays on Minecraft 26.2's backend-neutral GUI pipeline.</p>
 */
public final class JelloMainMenuScreen extends SigmaMainMenuScreen {

    private static final Identifier BACKGROUND = legacy("jello/background/background.png");
    private static final Identifier MIDDLE = legacy("jello/background/middle.png");
    private static final Identifier FOREGROUND = legacy("jello/background/foreground.png");
    private static final Identifier LOGO = legacy("jello/logo_large.png");
    private static final Identifier LOGO_2X = legacy("jello/logo_large@2x.png");
    private static final Identifier SHADOW = legacy("jello/shadow.png");

    private static final Identifier[] ICONS = {
        legacy("jello/icons/singleplayer.png"),
        legacy("jello/icons/multiplayer.png"),
        legacy("jello/icons/shop.png"),
        legacy("jello/icons/options.png"),
        legacy("jello/icons/alt.png")
    };

    private static final String[] ACTIONS = {"Singleplayer", "Multiplayer", "Realms", "Options", "Alt Manager"};

    private static final int LIGHT = ClientColors.LIGHT_GREYISH_BLUE.getColor();
    private static final int DEEP_TEAL = ClientColors.DEEP_TEAL.getColor();
    private static final int TEXT_DIM = withAlpha(LIGHT, 178);

    private final Animation[] actionHover = new Animation[ACTIONS.length];
    private final Animation exitHover = new Animation(160, 140, Animation.Direction.BACKWARDS);
    private final Animation changelogHover = new Animation(160, 140, Animation.Direction.BACKWARDS);
    private final Animation switchHover = new Animation(160, 140, Animation.Direction.BACKWARDS);

    public JelloMainMenuScreen() {
        super(Component.literal("Sigma Jello"));
        for (int i = 0; i < this.actionHover.length; i++) {
            this.actionHover[i] = new Animation(160, 140, Animation.Direction.BACKWARDS);
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        // The old menu was a three-layer scene. Keep the original PNG layers and only replace the old
        // fixed-function renderer; do not substitute the SwitchScreen background here.
        drawFullScreen(graphics, BACKGROUND);
        drawFullScreen(graphics, MIDDLE);
        drawFullScreen(graphics, FOREGROUND);

        int logoWidth = LegacyUiScale.size(336);
        int logoHeight = LegacyUiScale.size(178);
        int logoX = (this.width - logoWidth) / 2;
        int logoY = this.height / 2 - logoHeight;
        Identifier logo = this.minecraft.getWindow().getGuiScale() > 1 ? LOGO_2X : LOGO;
        graphics.blit(RenderPipelines.GUI_TEXTURED, logo, logoX, logoY, 0.0F, 0.0F,
            logoWidth, logoHeight, logoWidth, logoHeight, LIGHT);

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

            int drawSize = Math.max(1, Math.round(layout.size * (1.0F + motion * 0.20F)));
            int drawX = x - (drawSize - layout.size) / 2;
            int drawY = layout.y - (drawSize - layout.size) / 2
                - Math.round((layout.size / 2.0F) * motion * 0.20F);

            // Original MainMenuButton used Resources.shadowPNG with an 85px expansion around the icon.
            if (progress > 0.001F) {
                int shadowPad = LegacyUiScale.size(85);
                int shadowAlpha = Math.round(255.0F * Math.min(1.0F, progress * 0.70F));
                graphics.blit(RenderPipelines.GUI_TEXTURED, SHADOW,
                    drawX - shadowPad, drawY - shadowPad, 0.0F, 0.0F,
                    drawSize + shadowPad * 2, drawSize + shadowPad * 2,
                    drawSize + shadowPad * 2, drawSize + shadowPad * 2,
                    withAlpha(LIGHT, shadowAlpha));
            }

            graphics.blit(RenderPipelines.GUI_TEXTURED, ICONS[i], drawX, drawY, 0.0F, 0.0F,
                drawSize, drawSize, drawSize, drawSize, LIGHT);

            // In the original menu the name fades in below the image during hover rather than living in
            // a permanent card. Keep that behaviour; the exact old Jello font can be ported separately.
            if (progress > 0.001F) {
                String label = ACTIONS[i];
                int labelX = x + (layout.size - this.font.width(label)) / 2;
                int labelY = layout.y + layout.size - LegacyUiScale.size(40);
                graphics.text(this.font, label, labelX + 1, labelY + 1,
                    withAlpha(DEEP_TEAL, Math.round(progress * 96.0F)), false);
                graphics.text(this.font, label, labelX, labelY,
                    withAlpha(LIGHT, Math.round(progress * 153.0F)), false);
            }
        }

        // Historical top-bar positions after JelloMainMenu#updatePanelDimensions.
        this.drawTopAction(graphics, "Exit", 30, mouseX, mouseY, this.exitHover, 0.40F);
        this.drawTopAction(graphics, "Changelog", 90, mouseX, mouseY, this.changelogHover, 0.70F);
        this.drawTopAction(graphics, "Switch", 220, mouseX, mouseY, this.switchHover, 0.70F);

        String version = "Jello for Sigma " + Client.FULL_VERSION + "  -  Minecraft " + this.minecraft.getLaunchedVersion();
        graphics.text(this.font, "© Sigma Prod", LegacyUiScale.px(10), this.height - LegacyUiScale.size(16), LIGHT, true);
        graphics.text(this.font, version, this.width - this.font.width(version) - LegacyUiScale.size(9),
            this.height - LegacyUiScale.size(16), TEXT_DIM, true);
    }

    private static void drawFullScreen(final GuiGraphicsExtractor graphics, final Identifier texture) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0F, 0.0F,
            width, height, width, height);
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
        return new MenuLayout(size, stride, startX, y);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (inside(mouseX, mouseY, LegacyUiScale.px(30), LegacyUiScale.px(20),
            Math.max(this.font.width("Exit") + LegacyUiScale.size(8), LegacyUiScale.size(50)), LegacyUiScale.size(24))) {
            this.quitGame();
            return true;
        }
        if (inside(mouseX, mouseY, LegacyUiScale.px(220), LegacyUiScale.px(20),
            Math.max(this.font.width("Switch") + LegacyUiScale.size(8), LegacyUiScale.size(50)), LegacyUiScale.size(24))) {
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
                // Alt Manager is intentionally present in its original fifth slot. Its account-management
                // screen has not been ported to Sigma-Modern yet, so do not silently repurpose it as Language.
                case 4 -> { }
                default -> throw new IllegalStateException("Unexpected Jello menu action " + i);
            }
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    private static Identifier legacy(final String path) {
        return Identifier.withDefaultNamespace("textures/gui/sigma/legacy/" + path);
    }

    private static int withAlpha(final int color, final int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | color & 0x00FFFFFF;
    }

    private record MenuLayout(int size, int stride, int startX, int y) {
    }
}
