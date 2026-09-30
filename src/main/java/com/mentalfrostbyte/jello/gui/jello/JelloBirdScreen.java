package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import java.util.Random;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/** Jello's bird: a 700 x 512 board, Space to flap, the ground sliding under it and pipes coming in from the right. */
public final class JelloBirdScreen extends JelloGameScreen {
    private static final int WIDTH = 700;
    private static final int HEIGHT = 512;
    private static final int GROUND = 112;
    private static final int WHITE = 0xFFFEFEFE;
    /** The bird's sprite sheet is three frames side by side. */
    private static final int FRAME_WIDTH = 34;
    private static final int FRAMES = 3;

    private final BirdGame game = new BirdGame(new Random());

    public JelloBirdScreen() {
        super("Bird");
    }

    @Override
    int boardWidth() {
        return WIDTH;
    }

    @Override
    int boardHeight() {
        return HEIGHT;
    }

    @Override
    int score() {
        return this.game.score();
    }

    @Override
    void update(final float seconds) {
        this.game.update(seconds);
    }

    @Override
    void drawBoard(final LegacyCanvas c, final int x, final int y, final float alpha) {
        int colour = LegacyCanvas.alpha(WHITE, alpha);
        // Everything is cut to the board, so a pipe slides in from its edge rather than over the card.
        c.scissor(x, y, x + WIDTH, y + HEIGHT);
        try {
            for (int tile = 0; tile < 3; tile++) {
                c.image(LegacyTexture.BIRD_BACKGROUND, x + 288 * tile, y, 288, 512, colour);
            }

            for (BirdGame.Pipe pipe : this.game.pipes()) {
                float left = x + this.game.pipeX(pipe);
                if (left > x + WIDTH || left + BirdGame.PIPE_WIDTH < x) {
                    continue;
                }
                float gap = (float) (BirdGame.FIELD * pipe.gap);
                c.image(LegacyTexture.BIRD_PIPE_TOP, left, y + gap - BirdGame.GAP / 2.0F - 320, BirdGame.PIPE_WIDTH, 320, colour);
                c.image(LegacyTexture.BIRD_PIPE_BOTTOM, left, y + gap + BirdGame.GAP / 2.0F, BirdGame.PIPE_WIDTH, 320, colour);
            }

            // The ground scrolls one tile every 3.4 seconds.
            float scroll = (System.currentTimeMillis() % 3400L) / 3400.0F;
            for (int tile = 0; tile < 4; tile++) {
                c.image(LegacyTexture.BIRD_GROUND, x + 288 * tile - 288.0F * scroll, y + BirdGame.FIELD, 288, GROUND, colour);
            }

            int frame = (int) (System.currentTimeMillis() / 110L % FRAMES);
            c.region(LegacyTexture.BIRD.id, LegacyTexture.BIRD.width, LegacyTexture.BIRD.height, frame * FRAME_WIDTH, 0, FRAME_WIDTH, 24,
                x + BirdGame.BIRD_X, y + this.game.birdTop(), BirdGame.BIRD_W, BirdGame.BIRD_H, colour);
        } finally {
            c.unscissor();
        }
    }

    @Override
    boolean gameKey(final KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_SPACE) {
            this.game.flap();
            return true;
        }

        return false;
    }
}
