package com.mentalfrostbyte.jello.gui.mainmenu;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.util.client.render.theme.ClientColors;
import com.mentalfrostbyte.jello.util.math.SmoothInterpolator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Modern source-native counterpart of Sigma Classic's main menu. */
public final class ClassicMainMenuScreen extends SigmaMainMenuScreen {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/sigma/back.png");
    private static final Identifier CLASSIC_MARK = Identifier.withDefaultNamespace("textures/gui/sigma/classic.png");

    private static final int LIGHT = ClientColors.LIGHT_GREYISH_BLUE.getColor();
    private static final int DEEP_TEAL = ClientColors.DEEP_TEAL.getColor();
    private static final String[] ACTIONS = {"Singleplayer", "Multiplayer", "Options", "Language", "Switch", "Exit"};

    private final Animation[] actionHover = new Animation[ACTIONS.length];

    public ClassicMainMenuScreen() {
        super(Component.literal("Sigma Classic"));
        for (int i = 0; i < this.actionHover.length; i++) {
            this.actionHover[i] = new Animation(300, 300, Animation.Direction.BACKWARDS);
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, this.height, 1280, 720, 1280, 720);
        graphics.fill(0, 0, this.width, this.height, withAlpha(DEEP_TEAL, 82));

        ClassicLayout layout = this.layout();
        int markWidth = Math.min(300, Math.max(180, this.width - 80));
        int markHeight = markWidth * 97 / 300;
        int markX = (this.width - markWidth) / 2;
        int markY = Math.max(20, layout.firstRowY - markHeight - 22);
        graphics.blit(RenderPipelines.GUI_TEXTURED, CLASSIC_MARK, markX, markY, 0.0F, 0.0F,
            markWidth, markHeight, 264, 61, 264, 61, LIGHT);

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

            // Classic's historical BoxedButton was icon-led and had no stroke. Keep these interaction
            // zones transparent until the original uglygui PNG set can be transferred rather than
            // inventing a modern card. The tiny text offset keeps the old background-motion feedback.
            String label = ACTIONS[i];
            int textX = box.x + (box.width - this.font.width(label)) / 2;
            int textY = box.y + box.height - 28 - Math.round(Math.max(-2.0F, Math.min(2.0F, motion)));
            graphics.text(this.font, label, textX, textY + 1, withAlpha(DEEP_TEAL, 128), false);
            graphics.text(this.font, label, textX, textY, LIGHT, false);
        }

        String hello = "Hello," + this.minecraft.getUser().getName();
        graphics.text(this.font, hello, 10, this.height - 28, LIGHT, false);
        graphics.text(this.font, "You are using the latest version", 10, this.height - 16, LIGHT, false);
        String version = "Sigma " + Client.FULL_VERSION + " for Minecraft " + this.minecraft.getLaunchedVersion();
        graphics.text(this.font, version, this.width - this.font.width(version) - 9, this.height - 16, LIGHT, false);
    }

    private ClassicLayout layout() {
        int width = Math.min(114, Math.max(72, (this.width - 32) / 4));
        int height = Math.round(width * 140.0F / 114.0F);

        int firstOverlap = Math.max(3, Math.round(width * 6.0F / 114.0F));
        int firstStride = width - firstOverlap;
        int firstTotal = width + firstStride * 3;
        int firstStartX = (this.width - firstTotal) / 2;

        int secondGap = Math.max(4, Math.round(width * 6.0F / 114.0F));
        int secondStride = width + secondGap;
        int secondTotal = width + secondStride * 2;
        int secondStartX = (this.width - secondTotal) / 2;

        int firstRowY = Math.max(118, this.height / 2 - 86);
        int secondRowY = firstRowY + height + Math.max(8, Math.round(10.0F * width / 114.0F));
        if (secondRowY + height > this.height - 42) {
            int shift = secondRowY + height - (this.height - 42);
            firstRowY -= shift;
            secondRowY -= shift;
        }

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
                case 4 -> this.openModeSelect();
                case 5 -> this.quitGame();
                default -> throw new IllegalStateException("Unexpected Classic menu action " + i);
            }
            return true;
        }

        return super.mouseClicked(event, doubleClick);
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

            // The old second row was Accounts / Switch / Exit. Accounts is not implemented in Modern
            // yet, so leave its historical first slot empty and keep Switch/Exit in slots two and three.
            int historicalSecondIndex = index - 3;
            return new ButtonBox(
                this.secondStartX + historicalSecondIndex * this.secondStride,
                this.secondRowY,
                this.width,
                this.height
            );
        }
    }
}
