package com.mentalfrostbyte.jello.util.time;

/**
 * A stopwatch that only advances while it is running - a playback position, not a deadline.
 *
 * <p>{@link Timer} always measures against the present moment; this one accumulates elapsed time and
 * stops accumulating when {@link #stop()} is called, so {@link #getElapsed()} holds still until the
 * next {@link #start()}.</p>
 */
public class PausableTimer {

    private long resumedAtNanos;
    private long accumulatedNanos;
    private boolean running;

    public void start() {
        if (this.running) {
            return;
        }

        this.running = true;
        this.resumedAtNanos = System.nanoTime();
    }

    public void stop() {
        if (!this.running) {
            return;
        }

        this.accumulatedNanos += System.nanoTime() - this.resumedAtNanos;
        this.running = false;
    }

    /** Clears the accumulated time; a running timer keeps running from zero. */
    public void reset() {
        this.accumulatedNanos = 0L;
        this.resumedAtNanos = System.nanoTime();
    }

    public boolean isRunning() {
        return this.running;
    }

    /** Milliseconds this timer has spent running since the last {@link #reset()}. */
    public long getElapsed() {
        long total = this.accumulatedNanos;
        if (this.running) {
            total += System.nanoTime() - this.resumedAtNanos;
        }

        return total / 1_000_000L;
    }

    public void setElapsed(final long elapsed) {
        this.accumulatedNanos = elapsed * 1_000_000L;
        this.resumedAtNanos = System.nanoTime();
    }
}
