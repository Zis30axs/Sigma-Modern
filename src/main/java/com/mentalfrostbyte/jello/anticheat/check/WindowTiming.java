package com.mentalfrostbyte.jello.anticheat.check;

/**
 * The time base every check shares.
 *
 * <p>Samples carry the client's arrival time, which drifts from the server's clock: a lag spike delivers a
 * burst of samples at once. The number of server ticks a stretch of samples covers is therefore taken as the
 * <em>larger</em> of what the clock says and what the sample count says (the server reports a moving player
 * about every second tick). Overestimating the ticks only raises the distance a window may cover, so every
 * error leans towards not flagging.</p>
 */
public final class WindowTiming {

    public static final long TICK_NANOS = 50_000_000L;

    /** A window that saw no sample for this long is abandoned rather than judged. */
    public static final long GAP_RESET_NANOS = 3_000_000_000L;

    /** Server ticks between two consecutive position reports of a moving player. */
    private static final int TICKS_PER_SAMPLE = 2;

    private WindowTiming() {
    }

    /** Ticks a window of {@code segments} sample-to-sample steps spanning {@code elapsedNanos} covers. */
    public static double ticks(final long elapsedNanos, final int segments) {
        return Math.max((double) elapsedNanos / TICK_NANOS, (double) segments * TICKS_PER_SAMPLE);
    }
}
