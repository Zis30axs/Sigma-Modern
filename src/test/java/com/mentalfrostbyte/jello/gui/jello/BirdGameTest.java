package com.mentalfrostbyte.jello.gui.jello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BirdGameTest {

    private final BirdGame game = new BirdGame(new Random(5));

    @Test
    void startsHalfwayUpWithPipesOnTheWay() {
        assertEquals(0.5F, this.game.height());
        assertEquals(0, this.game.score());
        assertFalse(this.game.pipes().isEmpty());
        for (BirdGame.Pipe pipe : this.game.pipes()) {
            assertTrue(pipe.gap >= 0.25 && pipe.gap <= 0.75, "the gap is in the middle half");
        }
    }

    @Test
    void fallsFasterAndFasterAndAFlapLiftsItForAMoment() {
        this.game.update(1.0F / 60.0F);
        float afterOne = this.game.height();
        assertTrue(afterOne < 0.5F, "gravity");
        this.game.update(1.0F / 60.0F);
        float afterTwo = this.game.height();
        assertTrue(0.5F - afterOne < afterOne - afterTwo, "the drop grows each step");

        BirdGame flapped = new BirdGame(new Random(5));
        flapped.flap();
        flapped.update(1.0F / 60.0F);
        assertTrue(flapped.height() > 0.5F, "a flap outweighs gravity at first");
    }

    @Test
    void touchingTheGroundEndsTheRunAndStartsAnother() {
        boolean died = false;
        for (int i = 0; i < 240 && !died; i++) {
            this.game.update(1.0F / 60.0F);
            died = this.game.died();
        }

        assertTrue(died, "an unflapped bird ends up on the ground");
        assertEquals(0.5F, this.game.height(), "and begins again");
        assertEquals(0, this.game.score());
    }

    @Test
    void theSameFramesGiveTheSameFlightAtAnyFrameRate() {
        BirdGame a = new BirdGame(new Random(9));
        BirdGame b = new BirdGame(new Random(9));
        a.flap();
        b.flap();
        for (int i = 0; i < 6; i++) {
            a.update(1.0F / 60.0F);
        }
        for (int i = 0; i < 3; i++) {
            b.update(1.0F / 30.0F);
        }

        assertEquals(a.height(), b.height(), 1.0E-4F);
    }

    @Test
    void aPipePassedIsAPoint() {
        BirdGame g = new BirdGame(new Random(2));
        // A player who keeps the bird a little above the middle of the next gap gets through it: flap once it sinks below that.
        int frames = 0;
        while (g.score() == 0 && frames++ < 60 * 12) {
            BirdGame.Pipe next = g.pipes().get(0);
            for (BirdGame.Pipe pipe : g.pipes()) {
                if (g.pipeX(pipe) + BirdGame.PIPE_WIDTH > BirdGame.BIRD_X) {
                    next = pipe;
                    break;
                }
            }
            if (g.height() < 1.0F - (float) next.gap - 0.04F) {
                g.flap();
            }
            g.update(1.0F / 60.0F);
        }

        assertTrue(g.score() >= 1, "the bird got through a pipe");
    }
}
