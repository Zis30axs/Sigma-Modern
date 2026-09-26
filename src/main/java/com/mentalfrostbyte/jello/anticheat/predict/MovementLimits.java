package com.mentalfrostbyte.jello.anticheat.predict;

/**
 * How far a player can legally move, worked out from the vanilla movement rules rather than fitted to any
 * one report.
 *
 * <p>The client only sees where another player was every second tick, rounded, so a per-sample fit would
 * flag ordinary lag. Instead the checks ask for a <em>ceiling</em> on how fast anyone could possibly go and
 * compare that against a whole window of movement. The ceiling is built by simulating the best possible
 * player - one who accelerates straight ahead every tick, hops the moment they land, and never brakes - with
 * the same order of operations {@code LivingEntity.travelInAir} uses: input acceleration is added, the
 * player moves by the result, then friction scales what is left.</p>
 *
 * <p>The rules that differ between protocol versions (liquids, climbing, elytra, swimming) are outside what
 * the v1 checks judge. The ones inside - ground and air acceleration, the friction constants, the jump
 * impulse and the sprint-jump boost - are identical from 1.8 to the current version.</p>
 */
public final class MovementLimits {

    /** Forward and strafe input are each scaled by this before movement (LocalPlayer.aiStep). */
    private static final double INPUT_SCALE = 0.98;
    private static final double AIR_DRAG = 0.91;
    private static final double VERTICAL_DRAG = 0.98;
    /** Boost added along the facing direction by a sprinting jump. */
    private static final double SPRINT_JUMP_BOOST = 0.2;
    private static final double AIR_ACCEL = 0.02;
    private static final double AIR_ACCEL_SPRINTING = 0.025999999;
    /** A ground tick accelerates by speed * (0.216 / friction^3); for friction 0.6 that factor is exactly 1. */
    private static final double GROUND_ACCEL_NUMERATOR = 0.21600002;

    /** Input scale while using an item (eating, drawing a bow, blocking). */
    public static final double USING_ITEM_INPUT = 0.2;
    public static final double NORMAL_INPUT = 1.0;

    /** Ticks simulated; a multiple of the hop cycle, long enough to settle to a steady speed. */
    private static final int SIMULATED_TICKS = 480;
    private static final int SETTLE_TICKS = 240;

    private MovementLimits() {
    }

    /**
     * The most a player can travel horizontally per tick, on average, sustained.
     *
     * <p>A player who never left the ground cannot have hopped, so their ceiling is the walking one; a player
     * seen in the air gets the larger of walking and hopping every time they land (hopping is not always
     * faster, and the two are compared).</p>
     *
     * @param moveSpeed     movement speed attribute, sprint modifier included
     * @param friction      slipperiness of the block underfoot
     * @param inputFactor   {@link #NORMAL_INPUT}, or {@link #USING_ITEM_INPUT} while using an item
     * @param sprinting     whether the player is sprinting, which raises air acceleration and enables the jump boost
     * @param jumpStrength  jump strength attribute
     * @param mayHop        whether the player was seen off the ground, so hopping counts
     */
    public static double maxAveragePerTick(final double moveSpeed, final double friction, final double inputFactor,
                                           final boolean sprinting, final double jumpStrength, final boolean mayHop) {
        double walking = simulate(moveSpeed, friction, inputFactor, sprinting, jumpStrength, false);
        if (!mayHop) {
            return walking;
        }

        return Math.max(walking, simulate(moveSpeed, friction, inputFactor, sprinting, jumpStrength, true));
    }

    /**
     * As {@link #maxAveragePerTick}, for a player who stood on surfaces of different slipperiness. The
     * ceiling is not monotonic in friction (a slippery floor accelerates you more slowly and lets you slide
     * further), so the answer is the largest of the two extremes and the ordinary 0.6 rather than a guess at
     * which one is worst.
     */
    public static double maxAveragePerTick(final double moveSpeed, final double minFriction, final double maxFriction,
                                           final double inputFactor, final boolean sprinting, final double jumpStrength,
                                           final boolean mayHop) {
        double ceiling = maxAveragePerTick(moveSpeed, 0.6, inputFactor, sprinting, jumpStrength, mayHop);
        ceiling = Math.max(ceiling, maxAveragePerTick(moveSpeed, minFriction, inputFactor, sprinting, jumpStrength, mayHop));
        return Math.max(ceiling, maxAveragePerTick(moveSpeed, maxFriction, inputFactor, sprinting, jumpStrength, mayHop));
    }

    /**
     * Distance covered per tick averaged over the settled part of the simulation.
     */
    private static double simulate(final double moveSpeed, final double friction, final double inputFactor,
                                   final boolean sprinting, final double jumpStrength, final boolean hop) {
        // Diagonal input is the strongest: forward and strafe together, capped at unit length.
        double inputMagnitude = Math.min(1.0, INPUT_SCALE * Math.sqrt(2.0) * inputFactor);
        double groundSpeed = friction > 0.6
                ? moveSpeed * (GROUND_ACCEL_NUMERATOR / (friction * friction * friction))
                : moveSpeed;
        double groundAccel = groundSpeed * inputMagnitude;
        double airAccel = (sprinting ? AIR_ACCEL_SPRINTING : AIR_ACCEL) * inputMagnitude;
        double groundDrag = friction * AIR_DRAG;

        double velocity = 0.0;
        double y = 0.0;
        double vy = 0.0;
        boolean onGround = true;
        double travelled = 0.0;

        for (int tick = 0; tick < SIMULATED_TICKS; tick++) {
            if (hop && onGround) {
                vy = jumpStrength;
                if (sprinting) {
                    velocity += SPRINT_JUMP_BOOST;
                }
            }

            double accel = onGround ? groundAccel : airAccel;
            double drag = onGround ? groundDrag : AIR_DRAG;
            double step = velocity + accel;
            if (tick >= SETTLE_TICKS) {
                travelled += step;
            }

            velocity = step * drag;

            if (hop) {
                double next = y + vy;
                if (next <= 0.0 && vy <= 0.0) {
                    y = 0.0;
                    vy = 0.0;
                    onGround = true;
                } else {
                    y = next;
                    vy = (vy - 0.08) * VERTICAL_DRAG;
                    onGround = false;
                }
            }
        }

        return travelled / (SIMULATED_TICKS - SETTLE_TICKS);
    }

    /**
     * The highest a jump can lift a player above where they left the ground, with ordinary gravity.
     *
     * @param jumpStrength the jump strength attribute (0.42 for an ordinary jump)
     */
    public static double jumpApex(final double jumpStrength) {
        double y = 0.0;
        double vy = jumpStrength;
        double apex = 0.0;
        for (int tick = 0; tick < 200; tick++) {
            y += vy;
            apex = Math.max(apex, y);
            vy = (vy - 0.08) * VERTICAL_DRAG;
            if (vy < 0.0) {
                break;
            }
        }

        return apex;
    }
}
