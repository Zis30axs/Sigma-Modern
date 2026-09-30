package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacySounds;
import com.mentalfrostbyte.jello.gui.jello.SnakeGame.Cell;
import com.mentalfrostbyte.jello.gui.jello.SnakeGame.Turn;
import java.util.Random;
import net.minecraft.client.Options;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/** Jello's snake: 48 x 27 cells of 14 pixels, a step every 70 ms, steered with the game's own movement keys or the arrows. */
public final class JelloSnakeScreen extends JelloGameScreen {
    private static final int COLUMNS = 48;
    private static final int ROWS = 27;
    private static final int CELL = 14;
    private static final float STEP_SECONDS = 0.07F;
    private static final int BOARD = 0xFF010101;
    private static final int SNAKE = 0xFFFEFEFE;
    private static final int APPLE = 0xFFFFAA55;

    private final SnakeGame game = new SnakeGame(COLUMNS, ROWS, new Random());
    private float clock;

    public JelloSnakeScreen() {
        super("Snake");
    }

    @Override
    int boardWidth() {
        return COLUMNS * CELL;
    }

    @Override
    int boardHeight() {
        return ROWS * CELL;
    }

    @Override
    int score() {
        return this.game.score();
    }

    @Override
    void update(final float seconds) {
        this.clock += seconds;
        while (this.clock >= STEP_SECONDS) {
            this.clock -= STEP_SECONDS;
            if (this.game.step() == SnakeGame.Result.ATE) {
                LegacySounds.play(LegacySounds.Cue.POP);
            }
        }
    }

    @Override
    void drawBoard(final LegacyCanvas c, final int x, final int y, final float alpha) {
        c.fill(x, y, x + COLUMNS * CELL, y + ROWS * CELL, LegacyCanvas.fade(BOARD, alpha));
        Cell apple = this.game.apple();
        c.rounded(x + apple.x() * CELL, y + apple.y() * CELL, CELL, CELL, 5, LegacyCanvas.fade(APPLE, alpha));
        for (Cell cell : this.game.body()) {
            c.fill(x + cell.x() * CELL, y + cell.y() * CELL, x + (cell.x() + 1) * CELL, y + (cell.y() + 1) * CELL, LegacyCanvas.fade(SNAKE, alpha));
        }
    }

    @Override
    boolean gameKey(final KeyEvent event) {
        Options options = this.minecraft.options;
        Turn turn = null;
        if (options.keyUp.matches(event) || event.key() == GLFW.GLFW_KEY_UP) {
            turn = Turn.UP;
        } else if (options.keyDown.matches(event) || event.key() == GLFW.GLFW_KEY_DOWN) {
            turn = Turn.DOWN;
        } else if (options.keyLeft.matches(event) || event.key() == GLFW.GLFW_KEY_LEFT) {
            turn = Turn.LEFT;
        } else if (options.keyRight.matches(event) || event.key() == GLFW.GLFW_KEY_RIGHT) {
            turn = Turn.RIGHT;
        }

        if (turn == null) {
            return false;
        }

        this.game.turn(turn);
        return true;
    }
}
