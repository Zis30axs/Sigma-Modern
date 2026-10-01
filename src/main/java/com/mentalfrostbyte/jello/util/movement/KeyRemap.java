package com.mentalfrostbyte.jello.util.movement;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;

/**
 * Turns the direction keys from the way the camera faces to the way the server was told the player faces, so that the
 * player still walks where they meant to. Pure maths on the vanilla {@link Input}, testable without a game.
 *
 * <p>Movement is worked out from the keys' impulse ({@code x} to the left, {@code z} forward) turned by the facing.
 * Walking the way the camera and the keys say while the facing is another means impulses turned by the difference
 * between the two, {@code camera - reported}. A keyboard only has eight directions, so the turned impulse has to be
 * rounded to one of them: that rounding is where the versions here differ.</p>
 */
final class KeyRemap {

    /** The eight directions a keyboard makes, counter-clockwise from forward: {left, forward} impulses. */
    private static final int[][] DIRECTIONS = {
            {0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}
    };

    /** The keys that were made, and which of the eight directions they are; -1 when no direction key is held. */
    record Steered(Input keys, int direction) {
    }

    private KeyRemap() {
    }

    /**
     * LiquidBounce's {@code SILENT} ({@code MixinKeyboardInput.transformDirection}): each turned impulse rounded to the
     * nearest whole number on its own. For a single key the turned impulse is a unit vector, and each straight
     * direction then takes a 60 degree band while each diagonal takes only 30; a diagonal key, whose impulse is longer,
     * is split differently again. A value exactly halfway rounds up, so exactly on an edge the two sides are not
     * treated alike.
     */
    static Input silent(final Input keys, final float deltaYaw) {
        float x = impulse(keys.left(), keys.right());
        float z = impulse(keys.forward(), keys.backward());
        if (x == 0.0F && z == 0.0F) {
            // Upstream lets go of opposite keys held together; the movement is none either way, and the keys the
            // server is told about stay the ones the player holds.
            return keys;
        }

        float sin = Mth.sin(deltaYaw * Mth.DEG_TO_RAD);
        float cos = Mth.cos(deltaYaw * Mth.DEG_TO_RAD);
        float turnedX = x * cos - z * sin;
        float turnedZ = z * cos + x * sin;
        return withImpulses(keys, Math.round(turnedZ), Math.round(turnedX));
    }

    /**
     * The nearest of the eight directions to the turned impulse, by angle, so every direction gets an equal 45 degree
     * band and the same choice is made either side of the circle. {@code previous} is the direction chosen last tick
     * (-1 for none): it is kept for as long as it is within {@code hysteresis} degrees of the band's edge, so an aim
     * that wobbles across the edge between two directions does not flip the keys back and forth every tick.
     */
    static Steered nearest(final Input keys, final float deltaYaw, final int previous, final float hysteresis) {
        float x = impulse(keys.left(), keys.right());
        float z = impulse(keys.forward(), keys.backward());
        if (x == 0.0F && z == 0.0F) {
            return new Steered(keys, -1);
        }

        double rad = Math.toRadians(deltaYaw);
        double turnedX = x * Math.cos(rad) - z * Math.sin(rad);
        double turnedZ = z * Math.cos(rad) + x * Math.sin(rad);
        // 0 is forward, positive is to the left.
        double angle = Math.toDegrees(Math.atan2(turnedX, turnedZ));
        int best = Math.floorMod((int) Math.round(angle / 45.0), 8);
        if (previous >= 0 && previous != best
                && Math.abs(Mth.wrapDegrees((float) (angle - previous * 45.0))) <= 22.5F + hysteresis) {
            best = previous;
        }
        return new Steered(withImpulses(keys, DIRECTIONS[best][1], DIRECTIONS[best][0]), best);
    }

    private static float impulse(final boolean positive, final boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }

    /** {@code keys} with the direction keys those impulses press; the jump, sneak and sprint keys stay as they were. */
    private static Input withImpulses(final Input keys, final int forward, final int left) {
        return new Input(forward > 0, forward < 0, left > 0, left < 0, keys.jump(), keys.shift(), keys.sprint());
    }
}
