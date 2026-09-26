package com.mentalfrostbyte.jello.anticheat.observe;

/**
 * What the client can tell about another player's state from the entity data the server broadcasts,
 * packed into one int so a {@link Sample} stays small.
 *
 * <p>These are the flags the checks read. Anything in {@link #UNJUDGED} is a state whose movement rules
 * differ from plain walking and jumping, so v1 does not judge movement while one is set.</p>
 */
public final class PlayerFlags {

    public static final int SPRINTING = 1;
    public static final int SNEAKING = 1 << 1;
    public static final int USING_ITEM = 1 << 2;
    public static final int SWIMMING = 1 << 3;
    public static final int GLIDING = 1 << 4;
    /** Riptide trident spin: a launch the server never announces as a velocity packet. */
    public static final int SPIN_ATTACK = 1 << 5;
    public static final int PASSENGER = 1 << 6;
    public static final int SLEEPING = 1 << 7;
    /** Creative or spectator: free flight is legal, so there is nothing to judge. */
    public static final int EXEMPT_MODE = 1 << 8;
    public static final int DEAD = 1 << 9;

    /** Movement in any of these states is not judged. */
    public static final int UNJUDGED = SWIMMING | GLIDING | SPIN_ATTACK | PASSENGER | SLEEPING | EXEMPT_MODE | DEAD;

    private PlayerFlags() {
    }

    public static boolean has(final int flags, final int mask) {
        return (flags & mask) != 0;
    }
}
