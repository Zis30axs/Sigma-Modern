package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.click.ClickGuiHandler;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyLabelButton;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.util.math.Easing;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * "Jello for Sigma Options": the page the pause menu's extra button opens. It names the version, says which key opens
 * the ClickGUI and leads on to the Keybind Manager, the ClickGUI itself and the credits. It scales in from 1.3 times with
 * a little overshoot, and back out to 0.7 times with a fade when it hands over to one of them.
 */
public final class JelloOptionsScreen extends Screen {
    private static final int WHITE = 0xFFFEFEFE;

    private final Animation animation = new Animation(300, 200);
    private final LegacyLabelButton keybinds = new LegacyLabelButton("Open Keybind Manager", 0, 0, 300, 38, Face.JELLO_LIGHT, 24.0F, WHITE);
    private final LegacyLabelButton clickGui = new LegacyLabelButton("Open Jello's Click GUI", 0, 0, 300, 38, Face.JELLO_LIGHT, 24.0F, WHITE);
    private final LegacyLabelButton credits = new LegacyLabelButton("Credits", 0, 0, 200, 38, Face.JELLO_LIGHT, 18.0F, WHITE);
    private final @Nullable Screen parent;
    private @Nullable Runnable then;

    public JelloOptionsScreen(final @Nullable Screen parent) {
        super(Component.literal("Jello for Sigma Options"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The scene dims the way a vanilla in-game screen does, and the dimming thins as the page goes. */
    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        this.extractTransparentBackground(graphics);
    }

    private void leave(final Runnable next) {
        if (this.then == null) {
            this.then = next;
            this.animation.changeDirection(Animation.Direction.BACKWARDS);
        }
    }

    @Override
    public void tick() {
        if (this.then != null && this.animation.calcPercent() <= 0.0F) {
            Runnable next = this.then;
            this.then = null;
            next.run();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(null);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        super.extractRenderState(graphics, guiMouseX, guiMouseY, partialTick);
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            int w = c.width();
            int h = c.height();
            boolean leaving = this.then != null;
            float p = this.animation.calcPercent();
            float scale = leaving ? 0.7F + Easing.easeOutQuad(p, 0.0F, 1.0F, 1.0F) * 0.3F : 1.3F - Easing.easeOutBack(p, 0.0F, 1.0F, 1.0F) * 0.3F;
            float alpha = Math.min(1.0F, p);
            double mx = LegacyCanvas.mouseX();
            double my = LegacyCanvas.mouseY();

            // The block the old client laid out: the middle 60 % of the width, 80 % of the height (at least 420).
            int block = Math.max((int) (h * 0.8F), 420);
            int left = (int) (w * 0.2F);
            int top = h - block;
            int width = (int) (w * 0.8F) - left;
            int height = block - top;
            int x = left + 0;
            int y = top + 0;

            c.push();
            try {
                c.scaleAbout(scale, scale, w / 2.0F, h / 2.0F);
                float titleX = x + (width - 202) / 2.0F;
                c.text(Face.JELLO_MEDIUM, 40.0F, "Jello", titleX, y + 11, LegacyCanvas.alpha(WHITE, alpha));
                c.text(Face.JELLO_LIGHT, 25.0F, "for Sigma", titleX + 95, y + 24, LegacyCanvas.alpha(WHITE, 0.86F * alpha));
                String version = "You're currently using Sigma " + Client.FULL_VERSION;
                c.text(Face.JELLO_LIGHT, 20.0F, version, x + (width - c.textWidth(Face.JELLO_LIGHT, 20.0F, version)) / 2.0F, y + 70,
                    LegacyCanvas.alpha(WHITE, 0.4F * alpha));

                this.credits.x = x + width / 2 - 100;
                this.credits.y = y + height - 280;
                this.keybinds.x = x + width / 2 - 300;
                this.keybinds.y = y + height - 80;
                this.clickGui.x = x + width / 2;
                this.clickGui.y = y + height - 80;
                this.credits.draw(c, mx, my, alpha, !leaving);
                this.keybinds.draw(c, mx, my, alpha, !leaving);
                this.clickGui.draw(c, mx, my, alpha, !leaving);

                String bound = "Click GUI is currently bound to: " + ClickGuiHandler.OPEN_KEY.getDisplayName().getString() + " Key";
                c.text(Face.JELLO_LIGHT, 20.0F, bound, x + (width - c.textWidth(Face.JELLO_LIGHT, 20.0F, bound)) / 2.0F, y + height - 180,
                    LegacyCanvas.alpha(WHITE, 0.6F * alpha));
                String hint = "Configure all your keybinds in the keybind manager!";
                c.text(Face.JELLO_LIGHT, 14.0F, hint, x + (width - c.textWidth(Face.JELLO_LIGHT, 14.0F, hint)) / 2.0F, y + height - 150,
                    LegacyCanvas.alpha(WHITE, 0.4F * alpha));
            } finally {
                c.pop();
            }
        }
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() != 0 || this.then != null) {
            return true;
        }

        double mx = LegacyCanvas.toLegacy(event.x());
        double my = LegacyCanvas.toLegacy(event.y());
        if (this.keybinds.contains(mx, my)) {
            this.leave(() -> this.minecraft.gui.setScreen(new JelloKeybindScreen()));
        } else if (this.clickGui.contains(mx, my)) {
            this.leave(() -> this.minecraft.gui.setScreen(Client.getInstance().getPresentationManager().createClickGui(Client.getInstance().getModuleManager())));
        } else if (this.credits.contains(mx, my)) {
            this.leave(() -> this.minecraft.gui.setScreen(new JelloCreditsScreen(this.parent)));
        }

        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }

        return super.keyPressed(event);
    }
}
