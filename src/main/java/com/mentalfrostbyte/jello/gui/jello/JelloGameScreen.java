package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.gui.modern.ModernBlurredBackdrop;
import com.mentalfrostbyte.jello.util.game.render.GuiVisuals;
import com.mentalfrostbyte.jello.util.math.Easing;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The frame both of Jello's little games sit in: the world blurred behind, a white card with a soft edge holding the board,
 * the game's name in Helvetica Neue Medium above it on the left and "Max: n | Score: n" on the right. The whole thing
 * springs in from 0.8 times with a little overshoot.
 */
abstract class JelloGameScreen extends Screen implements ModernBlurredBackdrop {
    private static final int WHITE = 0xFFFEFEFE;
    private static final int INK = 0xFF010101;

    private final Animation appear = new Animation(200, 200);
    private final String name;
    private int best;
    private long lastFrame;

    JelloGameScreen(final String name) {
        super(Component.literal(name));
        this.name = name;
    }

    /** The board's size in framebuffer pixels. */
    abstract int boardWidth();

    abstract int boardHeight();

    abstract int score();

    /** Advances the game by {@code seconds}. */
    abstract void update(float seconds);

    /** Draws the board with its top-left at {@code (x, y)}. */
    abstract void drawBoard(LegacyCanvas c, int x, int y, float alpha);

    /** A key the game itself takes; the screen handles Escape. */
    abstract boolean gameKey(KeyEvent event);

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        GuiVisuals.blurBackground(graphics);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int guiMouseX, final int guiMouseY, final float partialTick) {
        try (LegacyCanvas c = new LegacyCanvas(graphics)) {
            long now = System.nanoTime();
            float dt = this.lastFrame == 0L ? 0.0F : Math.min(0.1F, (now - this.lastFrame) / 1.0E9F);
            this.lastFrame = now;
            this.update(dt);
            this.best = Math.max(this.best, this.score());

            float p = this.appear.calcPercent();
            float pop = Easing.easeOutBack(p, 0.0F, 1.0F, 1.0F);
            int w = c.width();
            int h = c.height();
            int boardW = this.boardWidth();
            int boardH = this.boardHeight();
            int x = (w - boardW) / 2;
            int y = (h - boardH) / 2 + 30;

            c.fill(0, 0, w, h, LegacyCanvas.alpha(INK, 0.25F * p));
            c.push();
            try {
                c.scaleAbout(0.8F + pop * 0.2F, 0.8F + pop * 0.2F, w / 2.0F, h / 2.0F);
                c.outerGlow(x, y, boardW, boardH, 40.0F, p);
                c.rounded(x - 20, y - 20, boardW + 40, boardH + 40, 14, WHITE);
                this.drawBoard(c, x, y, p);
                c.text(Face.JELLO_MEDIUM, 40.0F, this.name, x, y - 90, LegacyCanvas.alpha(WHITE, p));
                String scores = "Max: " + this.best + "   |   Score: " + this.score();
                c.text(Face.JELLO_LIGHT, 20.0F, scores, x + boardW - c.textWidth(Face.JELLO_LIGHT, 20.0F, scores), y - 80, LegacyCanvas.alpha(WHITE, 0.8F * p));
            } finally {
                c.pop();
            }
        }
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }

        return this.gameKey(event) || super.keyPressed(event);
    }
}
