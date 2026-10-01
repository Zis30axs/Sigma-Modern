package com.mentalfrostbyte.jello.gui.jello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.gui.jello.SnakeGame.Cell;
import com.mentalfrostbyte.jello.gui.jello.SnakeGame.Result;
import com.mentalfrostbyte.jello.gui.jello.SnakeGame.Turn;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SnakeGameTest {

    private final SnakeGame game = new SnakeGame(48, 27, new Random(7));

    @Test
    void beginsThreeLongHeadingLeftFromTheMiddle() {
        assertEquals(3, this.game.score());
        assertEquals(Turn.LEFT, this.game.direction());
        assertEquals(List.of(new Cell(22, 13), new Cell(23, 13), new Cell(24, 13)), this.game.body());
        assertFalse(this.game.body().contains(this.game.apple()), "the apple is not under the snake");
    }

    @Test
    void stepsOneCellAlongKeepingItsLength() {
        assertEquals(Result.MOVED, this.moveAwayFromTheApple());
        assertEquals(3, this.game.score());
        assertEquals(new Cell(21, 13), this.game.body().get(0));
    }

    @Test
    void cannotTurnStraightBackOrMoreThanOnceBetweenSteps() {
        this.game.turn(Turn.RIGHT);
        assertEquals(Turn.LEFT, this.game.direction(), "straight back is refused");

        this.game.turn(Turn.UP);
        this.game.turn(Turn.RIGHT);
        assertEquals(Turn.UP, this.game.direction(), "a second turn before the step waits");
        this.moveAwayFromTheApple();
        this.game.turn(Turn.RIGHT);
        assertEquals(Turn.RIGHT, this.game.direction(), "the next step allows it");
    }

    @Test
    void growsByOneWhenItReachesTheApple() {
        SnakeGame small = new SnakeGame(9, 5, new Random(1));
        boolean ate = false;
        for (int i = 0; i < 200 && !ate; i++) {
            Cell apple = small.apple();
            Cell head = small.body().get(0);
            // Head for the apple, taking the turn that does not fold it back on itself.
            if (apple.y() < head.y()) {
                small.turn(Turn.UP);
            } else if (apple.y() > head.y()) {
                small.turn(Turn.DOWN);
            } else if (apple.x() > head.x()) {
                small.turn(Turn.RIGHT);
            } else {
                small.turn(Turn.LEFT);
            }

            ate = small.step() == Result.ATE;
        }

        assertTrue(ate, "the snake reached an apple");
        int atTheApple = small.score();
        // The tail stays where it is on the step after eating, so the snake is one longer for it.
        Result next = small.step();
        if (next != Result.DIED) {
            assertEquals(atTheApple + 1, small.score());
        }
    }

    @Test
    void runningIntoTheEdgeStartsAgain() {
        Result last = Result.MOVED;
        for (int i = 0; i < 40 && last != Result.DIED; i++) {
            last = this.moveAwayFromTheApple();
        }

        assertEquals(Result.DIED, last);
        assertEquals(3, this.game.score());
        assertEquals(List.of(new Cell(22, 13), new Cell(23, 13), new Cell(24, 13)), this.game.body());
    }

    @Test
    void theAppleIsNeverPlacedOnTheSnake() {
        Random random = new Random(3);
        for (int i = 0; i < 200; i++) {
            SnakeGame g = new SnakeGame(6, 4, random);
            assertFalse(g.body().contains(g.apple()));
            assertNotEquals(null, g.apple());
        }
    }

    private Result moveAwayFromTheApple() {
        return this.game.step();
    }
}
