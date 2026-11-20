package com.mentalfrostbyte.jello.anticheat.check;

/**
 * What the checks are allowed to be, snapshotted from the module's settings once per tick so a check never
 * reads a setting mid-sample.
 *
 * @param speed             judge horizontal speed
 * @param flight            judge jump height, hovering, mid-air jumps and slow-fall gliding
 * @param groundSpoof       judge the on-ground flag
 * @param noSlow            judge speed while using an item
 * @param step              judge impossible ground-to-ground vertical step-ups
 * @param sneak             judge speed while sneaking
 * @param sprint            judge illegal sprinting states and backwards omni-sprint
 * @param rotation          judge impossible pitch values outside [-90, 90]
 * @param jesus             judge walking flat across liquid surfaces
 * @param toleranceScale    how much above the computed ceiling still counts as legal; 1.0 is the ceiling itself
 * @param burstTicks        ticks of extra distance a window is credited, for a player catching up after a lag spike
 * @param strikesRequired   consecutive windows over the ceiling before a speed flag
 * @param alertLevel        violation level a check must reach before the player is announced
 * @param quantStep         the protocol's position precision in blocks (1/4096 modern, 1/32 for 1.8)
 */
public record CheckSettings(boolean speed, boolean flight, boolean groundSpoof, boolean noSlow,
                            boolean step, boolean sneak, boolean sprint, boolean rotation, boolean jesus,
                            double toleranceScale, int burstTicks, int strikesRequired, double alertLevel,
                            double quantStep) {

    /** Backwards-compatible constructor enabling the five extended checks by default. */
    public CheckSettings(final boolean speed, final boolean flight, final boolean groundSpoof, final boolean noSlow,
                         final double toleranceScale, final int burstTicks, final int strikesRequired,
                         final double alertLevel, final double quantStep) {
        this(speed, flight, groundSpoof, noSlow, true, true, true, true, true,
                toleranceScale, burstTicks, strikesRequired, alertLevel, quantStep);
    }

    /** Every check on, at the balanced defaults, at modern precision. */
    public static CheckSettings balanced() {
        return new CheckSettings(true, true, true, true, true, true, true, true, true,
                1.15, 20, 2, 3.0, 1.0 / 4096.0);
    }

    public CheckSettings withQuantStep(final double step) {
        return new CheckSettings(this.speed, this.flight, this.groundSpoof, this.noSlow,
                this.step, this.sneak, this.sprint, this.rotation, this.jesus,
                this.toleranceScale, this.burstTicks, this.strikesRequired, this.alertLevel, step);
    }
}
