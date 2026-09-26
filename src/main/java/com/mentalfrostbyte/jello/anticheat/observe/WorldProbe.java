package com.mentalfrostbyte.jello.anticheat.observe;

/**
 * What the observer asks the world about a position when it takes a {@link Sample}. The production
 * implementation reads the client level; tests hand in a stub, so nothing above this interface needs the
 * game to run.
 */
public interface WorldProbe {

    /** Whether the chunk holding this column is loaded, i.e. whether the other answers are real. */
    boolean chunkLoaded(double x, double z);

    /** Slipperiness of the block the player would stand on at this position. */
    double blockFriction(double x, double y, double z);

    /**
     * Whether something solid - a block, or an entity players can stand on, such as a boat or a shulker -
     * touches a player's feet box at this position. The box is widened by {@code horizontalSlack} on each
     * side and reaches {@code verticalSlack} below the feet: slack for the rounding the protocol applied to
     * the position, and for the sideways step a moving player takes after the client has resolved the fall.
     */
    boolean supported(double x, double y, double z, double horizontalSlack, double verticalSlack);

    /**
     * Whether liquid, a climbable block, cobweb, honey, a bounce block or similar is around the player.
     * Movement there follows rules v1 does not model, so it is not judged.
     */
    boolean unjudgedEnvironment(double x, double y, double z);
}
