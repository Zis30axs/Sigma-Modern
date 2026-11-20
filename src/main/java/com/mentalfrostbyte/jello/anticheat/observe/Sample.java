package com.mentalfrostbyte.jello.anticheat.observe;

/**
 * One position report the server sent about a player, with everything the checks need already resolved.
 *
 * <p>The world lookups (friction, support, environment) are done when the sample is taken, on the game
 * thread, so the checks are plain functions of samples and can be tested without a game.</p>
 *
 * <p>The report is what the server last accepted from that player's own client, sampled about every second
 * tick and rounded to the protocol's position precision. A sample therefore spans anything from zero to a
 * few physics ticks, and its arrival time only roughly tracks the server's clock - the checks work on
 * windows of samples for that reason and never fit a single one.</p>
 *
 * @param nanos               when the client received it
 * @param onGround            the ground flag the player's own client reported
 * @param flags               {@link PlayerFlags}
 * @param moveSpeed           movement speed attribute, sprint and potion modifiers included
 * @param jumpStrength        jump strength attribute
 * @param gravity             gravity attribute
 * @param unknownEffects      the player shows potion particles, so some effect we cannot identify is active
 * @param friction            slipperiness of the block the player stands on (0.6 for ordinary ground)
 * @param supported           something solid - block or collidable entity - is under the feet
 * @param unjudgedEnvironment liquid, climbable, cobweb, honey, bounce block and the like are around the player
 * @param chunkLoaded         the chunk the player is in is loaded here, so the lookups above mean something
 * @param exempt              a teleport, velocity push or nearby block change happened just before this sample
 * @param yaw                 horizontal facing angle in degrees, or {@link Float#NaN} if unavailable
 * @param pitch               vertical facing angle in degrees, or {@link Float#NaN} if unavailable
 * @param stepHeight          step height attribute (0.6 by default)
 * @param sneakingSpeed       sneaking speed factor attribute (0.3 by default, up to 0.75 with Swift Sneak III)
 * @param overLiquid          fluid is directly under or at the player's feet
 */
public record Sample(long nanos, double x, double y, double z, boolean onGround, int flags,
                     double moveSpeed, double jumpStrength, double gravity, boolean unknownEffects,
                     double friction, boolean supported, boolean unjudgedEnvironment, boolean chunkLoaded,
                     boolean exempt, float yaw, float pitch, double stepHeight, double sneakingSpeed,
                     boolean overLiquid) {

    /** Ordinary jump strength, gravity, move speed, friction, step height and sneaking speed. */
    public static final double DEFAULT_JUMP_STRENGTH = 0.42;
    public static final double DEFAULT_GRAVITY = 0.08;
    public static final double DEFAULT_MOVE_SPEED = 0.1;
    public static final double DEFAULT_FRICTION = 0.6;
    public static final double DEFAULT_STEP_HEIGHT = 0.6;
    public static final double DEFAULT_SNEAKING_SPEED = 0.3;

    /** Legacy constructor for callers/tests that do not specify rotation, step, sneak or liquid probes. */
    public Sample(final long nanos, final double x, final double y, final double z, final boolean onGround,
                  final int flags, final double moveSpeed, final double jumpStrength, final double gravity,
                  final boolean unknownEffects, final double friction, final boolean supported,
                  final boolean unjudgedEnvironment, final boolean chunkLoaded, final boolean exempt) {
        this(nanos, x, y, z, onGround, flags, moveSpeed, jumpStrength, gravity, unknownEffects, friction,
                supported, unjudgedEnvironment, chunkLoaded, exempt, Float.NaN, Float.NaN,
                DEFAULT_STEP_HEIGHT, DEFAULT_SNEAKING_SPEED, false);
    }

    public boolean has(final int mask) {
        return PlayerFlags.has(this.flags, mask);
    }

    /** True when movement at this sample follows rules the standard movement checks understand. */
    public boolean judgeable() {
        return !this.exempt && this.chunkLoaded && !this.unjudgedEnvironment && (this.flags & PlayerFlags.UNJUDGED) == 0;
    }

    /** True when rotation/state checks can inspect this sample (ignores liquid/block movement exemptions). */
    public boolean stateJudgeable() {
        return !this.exempt && this.chunkLoaded && (this.flags & (PlayerFlags.EXEMPT_MODE | PlayerFlags.DEAD | PlayerFlags.SLEEPING)) == 0;
    }

    /** Horizontal distance from another sample. */
    public double horizontalDistanceTo(final Sample other) {
        return Math.hypot(this.x - other.x, this.z - other.z);
    }
}
