package com.mentalfrostbyte.jello.gui.jello;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * The rules of Jello's bird, with nothing to draw: a bird that falls and is flapped up, past pipes that come in from the right
 * at one every 2.2 seconds with their gaps at random heights.
 *
 * <p>The old client drew all this - the bird, the pipes, the scrolling ground - but never decided anything: the bird
 * flew through the pipes and the score stayed at 0, and its fall had no acceleration (a flap gave a nudge that faded,
 * against a constant drop of 600 pixels a second). Here the bird accelerates as it falls and a flap gives it a fixed upward
 * speed, touching a pipe or the ground ends the run and starts another, and each pipe passed is a point.</p>
 *
 * <p>Sizes are the old board's pixels: 400 of playfield above 112 of ground, pipes 52 wide with a gap of 100, coming at
 * 83 pixels a second. The physics runs on a fixed step of 1/60 s, so it plays the same at any frame rate.</p>
 */
final class BirdGame {
    static final int FIELD = 400;
    static final int PIPE_WIDTH = 52;
    static final int GAP = 100;
    static final float BIRD_X = 2200.0F / 12.0F;
    static final int BIRD_W = 34;
    static final int BIRD_H = 24;
    private static final float STEP = 1.0F / 60.0F;
    /** Per step, as fractions of the playfield: a fall that speeds up, and the upward speed a flap sets. */
    private static final float GRAVITY = 0.00085F;
    private static final float FLAP = 0.0145F;
    private static final float SPEED = 1000.0F / 12.0F;
    private static final long SPACING_MS = 2200L;

    /** One pipe: when it reaches the bird's column, and where its gap is (0.25 to 0.75 of the playfield). */
    static final class Pipe {
        final long atMs;
        final double gap;
        boolean scored;

        Pipe(final long atMs, final double gap) {
            this.atMs = atMs;
            this.gap = gap;
        }
    }

    private final Random random;
    private final List<Pipe> pipes = new ArrayList<>();
    private long clockMs;
    private float leftover;
    /** The bird's height above the ground line, 0 to 1 of the playfield. */
    private float height = 0.5F;
    private float velocity;
    private int score;
    private boolean died;

    BirdGame(final Random random) {
        this.random = random;
        this.reset();
    }

    void reset() {
        this.pipes.clear();
        this.clockMs = 0L;
        this.leftover = 0.0F;
        this.height = 0.5F;
        this.velocity = 0.0F;
        this.score = 0;
        this.died = false;
        this.fill();
    }

    float height() {
        return this.height;
    }

    int score() {
        return this.score;
    }

    long clockMs() {
        return this.clockMs;
    }

    List<Pipe> pipes() {
        return this.pipes;
    }

    /** Whether the last {@link #update} ended a run (and began the next). */
    boolean died() {
        return this.died;
    }

    void flap() {
        this.velocity = FLAP;
    }

    /** Where a pipe's left edge is, in pixels from the board's left edge. */
    float pipeX(final Pipe pipe) {
        return BIRD_X + (pipe.atMs - this.clockMs) / 12.0F;
    }

    /** The bird's top, in pixels down the playfield. */
    float birdTop() {
        return FIELD * (1.0F - this.height);
    }

    void update(final float seconds) {
        this.died = false;
        this.leftover += seconds;
        while (this.leftover >= STEP) {
            this.leftover -= STEP;
            this.clockMs += Math.round(STEP * 1000.0F);
            this.velocity -= GRAVITY;
            this.height = Math.max(0.0F, Math.min(1.0F, this.height + this.velocity));
            this.fill();
            if (this.collides()) {
                this.reset();
                this.died = true;
                return;
            }
            for (Pipe pipe : this.pipes) {
                if (!pipe.scored && this.pipeX(pipe) + PIPE_WIDTH < BIRD_X) {
                    pipe.scored = true;
                    this.score++;
                }
            }
        }
    }

    private boolean collides() {
        if (this.height <= 0.0F) {
            return true;
        }

        float top = this.birdTop();
        float bottom = top + BIRD_H;
        for (Pipe pipe : this.pipes) {
            float x = this.pipeX(pipe);
            if (x < BIRD_X + BIRD_W && x + PIPE_WIDTH > BIRD_X) {
                float gapCentre = (float) (FIELD * pipe.gap);
                if (top < gapCentre - GAP / 2.0F || bottom > gapCentre + GAP / 2.0F) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Keeps pipes coming for the next two spacings, and forgets the ones long gone. */
    private void fill() {
        if (this.pipes.isEmpty()) {
            this.pipes.add(new Pipe(this.clockMs + SPACING_MS * 2, 0.25 + this.random.nextDouble() * 0.5));
        }
        while (this.pipes.get(this.pipes.size() - 1).atMs < this.clockMs + SPACING_MS * 2) {
            this.pipes.add(new Pipe(this.pipes.get(this.pipes.size() - 1).atMs + SPACING_MS, 0.25 + this.random.nextDouble() * 0.5));
        }

        for (Iterator<Pipe> it = this.pipes.iterator(); it.hasNext(); ) {
            if (it.next().atMs < this.clockMs - SPACING_MS * 2) {
                it.remove();
            }
        }
    }
}
