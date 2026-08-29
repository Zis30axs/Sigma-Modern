package com.mentalfrostbyte.jello.util.math;

/**
 * The handful of numeric helpers vanilla does not already provide.
 *
 * <p>Clamping, interpolation, squaring, angle wrapping and angle differences all live in
 * {@link net.minecraft.util.Mth} - use those rather than reimplementing them here.</p>
 */
public final class MathUtil {

    private MathUtil() {
    }

    /**
     * Rounds {@code value} to the nearest multiple of {@code step}, e.g. quantising a rotation to the
     * precision the wire protocol actually transmits.
     */
    public static double roundToStep(final double value, final double step) {
        if (step == 0.0) {
            return value;
        }

        return Math.round(value / step) * step;
    }

    /**
     * A positive offset small enough to be invisible in gameplay but large enough to survive the
     * float conversion in a packet. Used to make otherwise identical outgoing values differ.
     */
    public static double tinyOffset() {
        return Math.random() * 1.0E-8;
    }
}
