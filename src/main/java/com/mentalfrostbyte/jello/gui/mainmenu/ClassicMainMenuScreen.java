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

/** Source-native 26.2 rendering of Sigma Classic's original main-menu artwork and layout. */
public final class ClassicMainMenuScreen extends SigmaMainMenuScreen {

    private static final Identifier BACKGROUND = legacy("classic/mainmenubackground.png");
    private static final Identifier BIG = legacy("classic/big.png");
    private static final Identifier[] ICONS = {
        legacy("classic/singleplayer.png"),
        legacy("classic/multiplayer.png"),
        legacy("classic/options.png"),
        legacy("classic/language.png"),
        legacy("classic/accounts.png"),
        legacy("classic/switch.png"),
        legacy("classic/exit.png")
    };
    private static final String[] ACTIONS = {
        "Singleplayer", "Multiplayer", "Options", "Language", "Accounts", "Switch", "Exit"
    };

    private static final int LIGHT = ClientColors.LIGHT_GREYISH_BLUE.getColor();
    private static final int DEEP_TEAL = ClientColors.DEEP_TEAL.getColor();

    private final Animation[] actionHover = new Animation[ACTIONS.length];

    public ClassicMainMenuScreen() {
        super(Component.literal("Sigma Classic"));
        for (int i = 0; i < this.actionHover.length; i++) {
            this.actionHover[i] = new Animation(300, 300, Animation.Direction.BACKWARDS);
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        int overscan = LegacyUiScale.size(10);
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, -overscan, -overscan, 0.0F, 0.0F,
            this.width + overscan * 2, this.height + overscan * 2,
            this.width + overscan * 2, this.height + overscan * 2);

        ClassicLayout layout = this.layout();

        // Resources.big is a 2x (600x195) source that Classic drew as 300x97.
        int markWidth = LegacyUiScale.size(300);
        int markHeight = LegacyUiScale.size(97);
        int markX = (this.width - markWidth) / 2;
        int markY = this.height / 2 - LegacyUiScale.size(200);
        graphics.blit(RenderPipelines.GUI_TEXTURED, BIG, markX, markY, 0.0F, 0.0F,
            markWidth, markHeight, markWidth, markHeight, LIGHT);

        for (int i = 0; i < ACTIONS.length; i++) {
            ButtonBox box = layout.box(i);
            boolean hovered = inside(mouseX, mouseY, box.x, box.y, box.width, box.height);
            Animation animation = this.actionHover[i];
            if (hovered && animation.calcPercent() < 0.10F) {
                animation.changeDirection(Animation.Direction.FORWARDS);
            } else if (!hovered && animation.calcPercent() >= 0.999F) {
                animation.changeDirection(Animation.Direction.BACKWARDS);
            }

            float progress = animation.calcPercent();
            float motion = animation.getDirection() == Animation.Direction.FORWARDS
                ? SmoothInterpolator.interpolate(progress, 0.68, 2.32, 0.06, 0.48)
                : SmoothInterpolator.interpolate(progress, 0.81, 0.38, 0.32, -1.53);

            // BoxedButton drew the original square PNG at x+20,y with a 100x100 display size.
            // Keep its quirky historical positioning instead of centring/redesigning the asset.
            int iconX = box.x + LegacyUiScale.px(20);
            int iconY = box.y;
            int iconSize = LegacyUiScale.size(100);
            int hoverLift = Math.round(LegacyUiScale.px(2.0F) * Math.max(-1.0F, Math.min(1.0F, motion)));
            graphics.blit(RenderPipelines.GUI_TEXTURED, ICONS[i], iconX, iconY - hoverLift, 0.0F, 0.0F,
                iconSize, iconSize, iconSize, iconSize, LIGHT);

            String label = ACTIONS[i];
            int labelX = box.x + LegacyUiScale.px(12) + (box.width - this.font.width(label)) / 2;
            int labelY = box.y + LegacyUiScale.px(102);
            graphics.text(this.font, label, labelX, labelY + 1, withAlpha(DEEP_TEAL, 128), false);
            graphics.text(this.font, label, labelX, labelY, LIGHT, false);
        }

        String hello = "Hello," + this.minecraft.getUser().getName();
        graphics.text(this.font, hello, LegacyUiScale.px(10), this.height - LegacyUiScale.size(28), LIGHT, false);
        graphics.text(this.font, "You are using the latest version", LegacyUiScale.px(10),
            this.height - LegacyUiScale.size(16), LIGHT, false);
        String version = "Sigma " + Client.FULL_VERSION + " for Minecraft " + this.minecraft.getLaunchedVersion();
        graphics.text(this.font, version, this.width - this.font.width(version) - LegacyUiScale.size(9),
            this.height - LegacyUiScale.size(16), LIGHT, false);
    }

    private ClassicLayout layout() {
        int width = LegacyUiScale.size(114);
        int height = LegacyUiScale.size(140);
        int firstStride = LegacyUiScale.size(116); // old 122 + (-6)
        int secondStride = LegacyUiScale.size(128); // old 122 + 6

        // Exact old ClassicMainScreenGroup formulas, translated from framebuffer pixels at the edge.
        int firstStartX = this.width / 2 - LegacyUiScale.px(244);
        int secondStartX = this.width / 2 - LegacyUiScale.px(204);
        int firstRowY = this.height / 2 - LegacyUiScale.px(80);
        int secondRowY = this.height / 2 + LegacyUiScale.px(70);
        return new ClassicLayout(width, height, firstStride, secondStride, firstStartX, secondStartX, firstRowY, secondRowY);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        ClassicLayout layout = this.layout();
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        for (int i = 0; i < ACTIONS.length; i++) {
            ButtonBox box = layout.box(i);
            if (!inside(mouseX, mouseY, box.x, box.y, box.width, box.height)) {
                continue;
            }

            switch (i) {
                case 0 -> this.openSingleplayer();
                case 1 -> this.openMultiplayer();
                case 2 -> this.openOptions();
                case 3 -> this.openLanguage();
                // Preserve the original Accounts slot. Sigma-Modern does not yet have the historical
                // account manager, so leave its action dormant instead of silently assigning another screen.
                case 4 -> { }
                case 5 -> this.openModeSelect();
                case 6 -> this.quitGame();
                default -> throw new IllegalStateException("Unexpected Classic menu action " + i);
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

    private record ButtonBox(int x, int y, int width, int height) {
    }

    private record ClassicLayout(
        int width,
        int height,
        int firstStride,
        int secondStride,
        int firstStartX,
        int secondStartX,
        int firstRowY,
        int secondRowY
    ) {
        private ButtonBox box(final int index) {
            if (index < 4) {
                return new ButtonBox(this.firstStartX + index * this.firstStride, this.firstRowY, this.width, this.height);
            }

            int secondIndex = index - 4;
            return new ButtonBox(this.secondStartX + secondIndex * this.secondStride, this.secondRowY, this.width, this.height);
        }
    }
}
