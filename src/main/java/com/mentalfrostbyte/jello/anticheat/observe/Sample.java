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
 * @param nanos             when the client received it
 * @param onGround          the ground flag the player's own client reported
 * @param flags             {@link PlayerFlags}
 * @param moveSpeed         movement speed attribute, sprint and potion modifiers included
 * @param jumpStrength      jump strength attribute
 * @param gravity           gravity attribute
 * @param unknownEffects    the player shows potion particles, so some effect we cannot identify is active
 * @param friction          slipperiness of the block the player stands on (0.6 for ordinary ground)
 * @param supported         something solid - block or collidable entity - is under the feet
 * @param unjudgedEnvironment liquid, climbable, cobweb, honey, bounce block and the like are around the player
 * @param chunkLoaded       the chunk the player is in is loaded here, so the lookups above mean something
 * @param exempt            a teleport, velocity push or nearby block change happened just before this sample
 */
public record Sample(long nanos, double x, double y, double z, boolean onGround, int flags,
                     double moveSpeed, double jumpStrength, double gravity, boolean unknownEffects,
                     double friction, boolean supported, boolean unjudgedEnvironment, boolean chunkLoaded,
                     boolean exempt) {

    /** Ordinary jump strength and gravity: anything else means an attribute is shifting the physics. */
    public static final double DEFAULT_JUMP_STRENGTH = 0.42;
    public static final double DEFAULT_GRAVITY = 0.08;
    public static final double DEFAULT_MOVE_SPEED = 0.1;
    public static final double DEFAULT_FRICTION = 0.6;

    public boolean has(final int mask) {
        return PlayerFlags.has(this.flags, mask);
    }

    /** True when movement at this sample follows rules the v1 checks understand. */
    public boolean judgeable() {
        return !this.exempt && this.chunkLoaded && !this.unjudgedEnvironment && (this.flags & PlayerFlags.UNJUDGED) == 0;
    }

    /** Horizontal distance from another sample. */
    public double horizontalDistanceTo(final Sample other) {
        return Math.hypot(this.x - other.x, this.z - other.z);
    }
}
