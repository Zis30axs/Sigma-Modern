package com.mentalfrostbyte.jello.anticheat.check;

/**
 * A player's violation level for one check, after Grim's {@code Check.violations}: every flag adds to it and
 * every clean stretch takes a little off, so a single odd sample fades away while a cheater keeps climbing.
 */
public final class ViolationBuffer {

    private double level;

    public double level() {
        return this.level;
    }

    /** Adds {@code weight} and returns the new level. */
    public double flag(final double weight) {
        this.level += weight;
        return this.level;
    }

    /** Takes {@code decay} off, never going below zero. */
    public void reward(final double decay) {
        this.level = Math.max(0.0, this.level - decay);
    }

    public void clear() {
        this.level = 0.0;
    }
}
