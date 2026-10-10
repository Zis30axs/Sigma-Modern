package com.mentalfrostbyte.jello.module.impl.combat;

import java.util.random.RandomGenerator;

/**
 * A varying click cadence sampled by the game tick. Game thread only.
 * Keeps less than one tick of rounding error, never a backlog of missed clicks. Max CPS caps a rolling
 * second, rather than each individual interval, so e.g. 12 CPS can use both one- and two-tick gaps.
 */
final class AstraClickScheduler {

    private static final long TICK_MS = 50L;
    private final RandomGenerator random;
    private final long[] clicks = new long[20];
    private int firstClick;
    private int clickCount;
    private boolean scheduled;
    private double nextClickAtMs;
    private double pace;

    AstraClickScheduler(final RandomGenerator random) {
        this.random = random;
    }

    boolean ready(final long nowMs, final float minCps, final float maxCps) {
        this.expireClicks(nowMs);
        if (!this.scheduled) {
            this.schedule(nowMs, minCps, maxCps);
            return false;
        }
        if (nowMs < this.nextClickAtMs) {
            return false;
        }
        if (this.clickCount > 0) {
            int last = (this.firstClick + this.clickCount - 1) % this.clicks.length;
            // Catch-up ticks after a stall must not emit a group of attacks at the same real time.
            if (nowMs - this.clicks[last] < TICK_MS) {
                return false;
            }
        }
        return this.clickCount < (int) Math.max(minCps, maxCps);
    }

    /** Only an actual attack consumes the pending click; a failed ray trace or cooldown does not. */
    void clicked(final long nowMs, final float minCps, final float maxCps) {
        this.expireClicks(nowMs);
        int index = (this.firstClick + this.clickCount) % this.clicks.length;
        this.clicks[index] = nowMs;
        if (this.clickCount < this.clicks.length) {
            this.clickCount++;
        } else {
            this.firstClick = (this.firstClick + 1) % this.clicks.length;
        }
        this.schedule(nowMs, minCps, maxCps);
    }

    private void schedule(final long nowMs, final float minCps, final float maxCps) {
        double low = Math.min(minCps, maxCps);
        double high = Math.max(minCps, maxCps);
        double wanted = low + this.random.nextDouble() * (high - low);
        // Let the base pace drift between clicks instead of independently replacing it every time.
        this.pace = this.scheduled ? this.pace + (wanted - this.pace) * 0.35D : wanted;
        double baseInterval = 1000.0D / this.pace;
        // Tick-scale variation survives polling at 20 TPS, even with equal Min/Max values.
        double spread = Math.max(TICK_MS, baseInterval * 0.35D);
        double interval = Math.max(TICK_MS,
                baseInterval + (this.random.nextDouble() - this.random.nextDouble()) * spread);
        if (this.random.nextDouble() < 0.04D) {
            interval += TICK_MS * (1 + this.random.nextInt(3));
        }

        double lateness = nowMs - this.nextClickAtMs;
        double rounding = this.scheduled && lateness >= 0.0D && lateness < TICK_MS ? lateness : 0.0D;
        this.nextClickAtMs = nowMs + Math.max(TICK_MS, interval - rounding);
        this.scheduled = true;
    }

    private void expireClicks(final long nowMs) {
        while (this.clickCount > 0 && nowMs - this.clicks[this.firstClick] >= 1000L) {
            this.firstClick = (this.firstClick + 1) % this.clicks.length;
            this.clickCount--;
        }
    }

    /** Drop the pending click without letting target/config/toggle changes refill the rate limit. */
    void restart() {
        this.scheduled = false;
        this.nextClickAtMs = 0.0D;
        this.pace = 0.0D;
    }

    void reset() {
        this.restart();
        this.firstClick = 0;
        this.clickCount = 0;
    }
}
