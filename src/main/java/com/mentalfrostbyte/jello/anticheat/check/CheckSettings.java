package com.mentalfrostbyte.jello.anticheat.check;

/**
 * What the checks are allowed to be, snapshotted from the module's settings once per tick so a check never
 * reads a setting mid-sample.
 *
 * @param speed             judge horizontal speed
 * @param flight            judge jump height and hovering
 * @param groundSpoof       judge the on-ground flag
 * @param noSlow            judge speed while using an item
 * @param toleranceScale    how much above the computed ceiling still counts as legal; 1.0 is the ceiling itself
 * @param burstTicks        ticks of extra distance a window is credited, for a player catching up after a lag spike
 * @param strikesRequired   consecutive windows over the ceiling before a speed flag
 * @param alertLevel        violation level a check must reach before the player is announced
 * @param quantStep         the protocol's position precision in blocks (1/4096 modern, 1/32 for 1.8)
 */
public record CheckSettings(boolean speed, boolean flight, boolean groundSpoof, boolean noSlow,
                            double toleranceScale, int burstTicks, int strikesRequired, double alertLevel,
                            double quantStep) {

    /** Every check on, at the balanced defaults, at modern precision. */
    public static CheckSettings balanced() {
        return new CheckSettings(true, true, true, true, 1.15, 20, 2, 3.0, 1.0 / 4096.0);
    }

    public CheckSettings withQuantStep(final double step) {
        return new CheckSettings(this.speed, this.flight, this.groundSpoof, this.noSlow, this.toleranceScale,
                this.burstTicks, this.strikesRequired, this.alertLevel, step);
    }
}
