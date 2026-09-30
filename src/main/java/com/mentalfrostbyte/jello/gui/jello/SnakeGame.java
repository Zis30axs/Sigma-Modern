package com.mentalfrostbyte.jello.gui.jello;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The rules of Jello's snake, with nothing to draw: a snake of cells on a grid that grows an apple at a time and starts
 * again when it runs into the edge or itself. It is the old client's game, played the same way - it begins three cells long
 * heading left from the middle, cannot turn back on itself, and turns at most once between two steps, so two quick key presses
 * cannot fold it into its own neck.
 */
final class SnakeGame {
    enum Turn {
        UP(0, -1),
        DOWN(0, 1),
        LEFT(-1, 0),
        RIGHT(1, 0);

        final int dx;
        final int dy;

        Turn(final int dx, final int dy) {
            this.dx = dx;
            this.dy = dy;
        }
    }

    enum Result { MOVED, ATE, DIED }

    record Cell(int x, int y) {}

    private final int columns;
    private final int rows;
    private final Random random;
    private final List<Cell> body = new ArrayList<>();
    private Turn direction;
    private boolean turned;
    private boolean growing;
    private Cell apple;

    SnakeGame(final int columns, final int rows, final Random random) {
        this.columns = columns;
        this.rows = rows;
        this.random = random;
        this.reset();
    }

    int columns() {
        return this.columns;
    }

    int rows() {
        return this.rows;
    }

    /** The cells from the head to the tail. */
    List<Cell> body() {
        return this.body;
    }

    Cell apple() {
        return this.apple;
    }

    Turn direction() {
        return this.direction;
    }

    /** The length, which is the score: a new game starts at 3. */
    int score() {
        return this.body.size();
    }

    void reset() {
        this.direction = Turn.LEFT;
        this.turned = false;
        this.growing = false;
        Cell middle = new Cell(this.columns / 2, this.rows / 2);
        this.body.clear();
        this.body.add(new Cell(middle.x() + 2 * this.direction.dx, middle.y() + 2 * this.direction.dy));
        this.body.add(new Cell(middle.x() + this.direction.dx, middle.y() + this.direction.dy));
        this.body.add(middle);
        this.apple = this.newApple();
    }

    /** Asks to go {@code turn}: refused when it is straight back or no change, or when it already turned since the last step. */
    void turn(final Turn turn) {
        boolean back = turn.dx + this.direction.dx == 0 && turn.dy + this.direction.dy == 0;
        if (!back && turn != this.direction && !this.turned) {
            this.direction = turn;
            this.turned = true;
        }
    }

    Result step() {
        Cell head = this.body.get(0);
        Cell next = new Cell(head.x() + this.direction.dx, head.y() + this.direction.dy);
        boolean hitSelf = this.body.contains(next);
        this.body.add(0, next);
        if (!this.growing) {
            this.body.remove(this.body.size() - 1);
        }

        this.turned = false;
        this.growing = false;
        if (hitSelf || !this.inside(next)) {
            this.reset();
            return Result.DIED;
        }
        if (next.equals(this.apple)) {
            this.apple = this.newApple();
            this.growing = true;
            return Result.ATE;
        }

        return Result.MOVED;
    }

    private boolean inside(final Cell cell) {
        return cell.x() >= 0 && cell.y() >= 0 && cell.x() < this.columns && cell.y() < this.rows;
    }

    /** A free cell on the board; the snake's own if it somehow fills the whole of it. */
    private Cell newApple() {
        if (this.body.size() >= this.columns * this.rows) {
            return this.body.get(0);
        }

        Cell cell;
        do {
            cell = new Cell(this.random.nextInt(this.columns), this.random.nextInt(this.rows));
        } while (this.body.contains(cell));

        return cell;
    }
}
