package com.mentalfrostbyte.jello.util.time;

/**
 * Millisecond stopwatch: the "how long since I last did this" primitive modules use for cooldowns and
 * delays.
 *
 * <p>Replaces the old {@code Counter} and {@code TimerUtils}, which were the same class twice over
 * with slightly different method names and one off-by-a-comparison difference. Backed by
 * {@link System#nanoTime()} rather than wall-clock time, so a system clock adjustment cannot make a
 * cooldown fire early or hang forever.</p>
 */
public class Timer {

    private long markNanos = System.nanoTime();

    /** Restarts the stopwatch from now. */
    public void reset() {
        this.markNanos = System.nanoTime();
    }

    /** Milliseconds since the last {@link #reset()}. */
    public long getElapsed() {
        return (System.nanoTime() - this.markNanos) / 1_000_000L;
    }

    /** Pretends the last reset happened {@code elapsed} milliseconds ago. */
    public void setElapsed(final long elapsed) {
        this.markNanos = System.nanoTime() - elapsed * 1_000_000L;
    }

    public boolean hasElapsed(final long milliseconds) {
        return this.getElapsed() >= milliseconds;
    }

    /** As {@link #hasElapsed(long)}, resetting the stopwatch when the delay has passed. */
    public boolean hasElapsed(final long milliseconds, final boolean resetOnSuccess) {
        if (!this.hasElapsed(milliseconds)) {
            return false;
        }

        if (resetOnSuccess) {
            this.reset();
        }

        return true;
    }
}
