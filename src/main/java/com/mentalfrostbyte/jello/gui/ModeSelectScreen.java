package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.mainmenu.MainMenuRouter;
import com.mentalfrostbyte.jello.util.client.render.LegacyUiScale;
import com.mentalfrostbyte.jello.util.client.render.theme.ClientColors;
import com.mentalfrostbyte.jello.util.math.SmoothInterpolator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Main-menu mode selector, preserving Sigma's original SwitchScreen flow. */
public final class ModeSelectScreen extends Screen {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/sigma/back.png");
    private static final Identifier LOGO = Identifier.withDefaultNamespace("textures/gui/sigma/logo.png");
    private static final Identifier NO_ADDONS_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/noaddons.png");
    private static final Identifier CLASSIC_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/classic.png");
    private static final Identifier JELLO_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/jello.png");
    private static final Identifier YOUTUBE_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/youtube.png");
    private static final Identifier REDDIT_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/reddit.png");
    private static final Identifier GUILDED_IMAGE = Identifier.withDefaultNamespace("textures/gui/sigma/guilded.png");

    // Historical SwitchScreen measurements are framebuffer pixels, not modern GUI units.
    private static final int NO_ADDONS_WIDTH = 537;
    private static final int NO_ADDONS_HEIGHT = 93;
    private static final int SMALL_WIDTH = 264;
    private static final int SMALL_HEIGHT = 61;
    private static final int GAP = 9;
    private static final int LOGO_WIDTH = 455;
    private static final int LOGO_HEIGHT = 78;

    private static final int LIGHT = ClientColors.LIGHT_GREYISH_BLUE.getColor();
    private static final int DEEP_TEAL = ClientColors.DEEP_TEAL.getColor();

    private final @Nullable Screen returnScreen;
    private final boolean selectionRequired;
    private final Animation noAddonsHover = new Animation(150, 190, Animation.Direction.BACKWARDS);
    private final Animation classicHover = new Animation(150, 190, Animation.Direction.BACKWARDS);
    private final Animation jelloHover = new Animation(150, 190, Animation.Direction.BACKWARDS);

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
        graphics.fill(0, 0, this.width, this.height, withAlpha(DEEP_TEAL, 77));

        int logoWidth = Math.min(LegacyUiScale.size(LOGO_WIDTH), Math.max(1, this.width - LegacyUiScale.size(40)));
        int logoHeight = Math.max(1, logoWidth * LOGO_HEIGHT / LOGO_WIDTH);
        int logoX = (this.width - logoWidth) / 2;
        int logoY = LegacyUiScale.px(24);
        graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, logoX, logoY, 0.0F, 0.0F,
            logoWidth, logoHeight, 910, 156, 910, 156, LIGHT);

        ModeLayout layout = this.layout(logoY, logoHeight);
        this.drawModeCard(graphics, NO_ADDONS_IMAGE, ClientMode.NO_ADDONS, this.noAddonsHover,
            layout.x, layout.y, layout.bigWidth, layout.bigHeight, NO_ADDONS_WIDTH, NO_ADDONS_HEIGHT, mouseX, mouseY);
        this.drawModeCard(graphics, CLASSIC_IMAGE, ClientMode.CLASSIC, this.classicHover,
            layout.x, layout.y + layout.bigHeight + layout.gap, layout.smallWidth, layout.smallHeight,
            SMALL_WIDTH, SMALL_HEIGHT, mouseX, mouseY);
        this.drawModeCard(graphics, JELLO_IMAGE, ClientMode.JELLO, this.jelloHover,
            layout.x + layout.smallWidth + layout.gap, layout.y + layout.bigHeight + layout.gap,
            layout.smallWidth, layout.smallHeight, SMALL_WIDTH, SMALL_HEIGHT, mouseX, mouseY);

        this.drawSocialButtons(graphics);
    }

    private void drawModeCard(
        final GuiGraphicsExtractor graphics,
        final Identifier texture,
        final ClientMode mode,
        final Animation animation,
        final int x,
        final int y,
        final int width,
        final int height,
        final int textureWidth,
        final int textureHeight,
        final int mouseX,
        final int mouseY
    ) {
        boolean hovered = inside(mouseX, mouseY, x, y, width, height);
        animation.changeDirection(hovered ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        float progress = animation.calcPercent();
        float motion = animation.getDirection() == Animation.Direction.FORWARDS
            ? SmoothInterpolator.interpolate(progress, 0.07, 0.73, 0.63, 1.01)
            : SmoothInterpolator.interpolate(progress, 0.71, 0.18, 0.95, 0.57);
        int lift = Math.round(LegacyUiScale.px(3.0F) * motion);
        int drawY = y - lift;

        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, drawY, 0.0F, 0.0F,
            width, height, textureWidth, textureHeight, textureWidth, textureHeight, LIGHT);

        if (hovered) {
            graphics.fill(x, drawY, x + width, drawY + height, withAlpha(DEEP_TEAL, 96));
        } else if (!this.selectionRequired && Client.getInstance().getClientModeManager().get() == mode) {
            graphics.fill(x, drawY, x + width, drawY + height, withAlpha(LIGHT, 18));
        }
    }

    private ModeLayout layout(final int logoY, final int logoHeight) {
        int sourceBigWidth = LegacyUiScale.size(NO_ADDONS_WIDTH);
        int sourceBigHeight = LegacyUiScale.size(NO_ADDONS_HEIGHT);
        int sourceSmallWidth = LegacyUiScale.size(SMALL_WIDTH);
        int sourceSmallHeight = LegacyUiScale.size(SMALL_HEIGHT);
        int sourceGap = LegacyUiScale.size(GAP);

        float fitScale = Math.min(1.0F, Math.max(0.1F, (this.width - LegacyUiScale.size(24)) / (float) sourceBigWidth));
        int bigWidth = Math.max(1, Math.round(sourceBigWidth * fitScale));
        int bigHeight = Math.max(1, Math.round(sourceBigHeight * fitScale));
        int smallWidth = Math.max(1, Math.round(sourceSmallWidth * fitScale));
        int smallHeight = Math.max(1, Math.round(sourceSmallHeight * fitScale));
        int gap = Math.max(1, Math.round(sourceGap * fitScale));

        int cardsTop = logoY + logoHeight + LegacyUiScale.size(28);
        int cardsTotalHeight = bigHeight + gap + smallHeight;
        int footerReserve = LegacyUiScale.size(70);
        int y = cardsTop + Math.max(0, (this.height - cardsTop - cardsTotalHeight - footerReserve) / 2);
        int x = (this.width - bigWidth) / 2;
        return new ModeLayout(x, y, bigWidth, bigHeight, smallWidth, smallHeight, gap);
    }

    private void drawSocialButtons(final GuiGraphicsExtractor graphics) {
        int totalWidth = LegacyUiScale.size(174);
        int x = (this.width - totalWidth) / 2;
        int y = this.height - LegacyUiScale.size(70);
        if (y < 0 || x < 0) {
            return;
        }

        graphics.blit(RenderPipelines.GUI_TEXTURED, YOUTUBE_IMAGE, x, y, 0.0F, 0.0F,
            LegacyUiScale.size(65), LegacyUiScale.size(34), 65, 34, 65, 34, LIGHT);
        graphics.blit(RenderPipelines.GUI_TEXTURED, REDDIT_IMAGE, x + LegacyUiScale.px(85), y, 0.0F, 0.0F,
            LegacyUiScale.size(36), LegacyUiScale.size(34), 36, 34, 36, 34, LIGHT);
        graphics.blit(RenderPipelines.GUI_TEXTURED, GUILDED_IMAGE, x + LegacyUiScale.px(142), y, 0.0F, 0.0F,
            LegacyUiScale.size(32), LegacyUiScale.size(34), 32, 34, 32, 34, LIGHT);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        int logoWidth = Math.min(LegacyUiScale.size(LOGO_WIDTH), Math.max(1, this.width - LegacyUiScale.size(40)));
        int logoHeight = Math.max(1, logoWidth * LOGO_HEIGHT / LOGO_WIDTH);
        ModeLayout layout = this.layout(LegacyUiScale.px(24), logoHeight);
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        ClientMode selected = null;
        if (inside(mouseX, mouseY, layout.x, layout.y, layout.bigWidth, layout.bigHeight)) {
            selected = ClientMode.NO_ADDONS;
        } else if (inside(mouseX, mouseY, layout.x, layout.y + layout.bigHeight + layout.gap, layout.smallWidth, layout.smallHeight)) {
            selected = ClientMode.CLASSIC;
        } else if (inside(mouseX, mouseY, layout.x + layout.smallWidth + layout.gap,
            layout.y + layout.bigHeight + layout.gap, layout.smallWidth, layout.smallHeight)) {
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

    private static int withAlpha(final int color, final int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | color & 0x00FFFFFF;
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

    private record ModeLayout(int x, int y, int bigWidth, int bigHeight, int smallWidth, int smallHeight, int gap) {
    }
}
