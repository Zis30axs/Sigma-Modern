package com.mentalfrostbyte.jello.util.movement;

/**
 * How the player's walking is kept in step with a facing that is only reported to the server.
 *
 * <p>A module that reports a facing other than the camera's (a silent {@code KillAura}, a scaffold looking at a block)
 * leaves the server predicting the player's movement from that facing, while the client works it out from the camera's:
 * the two disagree and the prediction anticheat sees movement the reported facing cannot explain. A correction makes
 * the client's movement the one the reported facing explains.</p>
 *
 * <p>{@link #STRICT} and {@link #SILENT} follow LiquidBounce's {@code MovementCorrection} (nextgen, GPL-3.0, Copyright
 * (c) 2015 - 2026 CCBlueX); {@link #CLAUDE3} is this client's own.</p>
 */
public enum MovementCorrection {

    /** Nothing is corrected: the player walks by the camera. What the server predicts from the reported facing is then wrong. */
    OFF,

    /**
     * The keys push along the reported facing, and so does the sprint-jump boost, elytra and swimming. The movement
     * is a vanilla client's facing that way, but it is no longer the way the player pointed the keys.
     */
    STRICT,

    /**
     * {@link #STRICT}, and the direction keys are turned by the difference between the camera and the reported facing,
     * each rounded to a key, so the player still walks about the way they meant to.
     */
    SILENT,

    /**
     * {@link #STRICT}, with the keys turned to the nearest of the eight directions a keyboard can make, holding on to
     * the last one while the direction only wavers around the edge between two.
     */
    CLAUDE3;

    /** Whether the direction keys are replaced by others. */
    public boolean turnsKeys() {
        return this == SILENT || this == CLAUDE3;
    }

    /** Whether anything is corrected at all. */
    public boolean corrects() {
        return this != OFF;
    }
}
