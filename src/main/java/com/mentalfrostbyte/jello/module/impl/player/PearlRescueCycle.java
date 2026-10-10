package com.mentalfrostbyte.jello.module.impl.player;

/** A single bounded rescue attempt: planning can change, but its deadline and one-throw limit cannot. */
final class PearlRescueCycle {
    enum Stage { IDLE, SEARCHING, AIMING, WAITING }

    private Stage stage = Stage.IDLE;
    private long hardDeadline;
    private long arrivalDeadline;

    Stage stage() {
        return this.stage;
    }

    boolean begin(final long tick, final int maxHoldTicks) {
        if (this.stage != Stage.IDLE) return false;
        this.stage = Stage.SEARCHING;
        this.hardDeadline = after(tick, Math.clamp(maxHoldTicks, 1, 200));
        this.arrivalDeadline = this.hardDeadline;
        return true;
    }

    /** Keeps the original expiry even when planning is repeated or the caller has already timed out. */
    void planned() {
        if (this.stage == Stage.SEARCHING) this.stage = Stage.AIMING;
    }

    boolean shot(final long tick, final double flightTicks) {
        if (this.stage != Stage.AIMING || this.expired(tick)
                || !Double.isFinite(flightTicks) || flightTicks < 0) return false;
        int waitTicks = (int) Math.min(200, Math.ceil(flightTicks) + 20);
        this.arrivalDeadline = Math.min(this.hardDeadline, after(tick, waitTicks));
        this.stage = Stage.WAITING;
        return true;
    }

    boolean expired(final long tick) {
        return this.stage != Stage.IDLE && tick >= this.deadline();
    }

    int remainingTicks(final long tick) {
        if (this.stage == Stage.IDLE || tick >= this.deadline()) return 0;
        long remaining = this.deadline() - tick;
        // Saturate an out-of-order counter as well as the normal, at-most-200-tick rescue interval.
        return remaining < 0 ? 200 : (int) Math.min(200, remaining);
    }

    void reset() {
        this.stage = Stage.IDLE;
        this.hardDeadline = 0;
        this.arrivalDeadline = 0;
    }

    private long deadline() {
        return this.stage == Stage.WAITING ? this.arrivalDeadline : this.hardDeadline;
    }

    private static long after(final long tick, final int duration) {
        return tick > Long.MAX_VALUE - duration ? Long.MAX_VALUE : tick + duration;
    }
}
