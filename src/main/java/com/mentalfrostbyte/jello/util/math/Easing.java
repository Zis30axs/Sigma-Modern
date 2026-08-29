package com.mentalfrostbyte.jello.util.math;

/**
 * Robert Penner easing curves, in the four-argument form the interface code uses:
 * {@code ease(progress, start, change, duration)} maps {@code progress} within {@code [0, duration]}
 * onto {@code [start, start + change]}.
 *
 * <p>Replaces the old split across three classes (quadratic ones in {@code QuadraticEasing}, the back
 * ones in {@code EasingFunctions}, the cubic ones mixed into a "MathHelper" alongside audio decoding).
 * The fixed-overshoot back curves now delegate to the parameterised ones instead of repeating the
 * polynomial with a hard-coded constant.</p>
 */
public final class Easing {

    /** The overshoot Penner's back curves use when none is given. */
    public static final float DEFAULT_OVERSHOOT = 1.70158F;

    private Easing() {
    }

    public static float easeInQuad(final float progress, final float start, final float change, final float duration) {
        float t = progress / duration;
        return change * t * t + start;
    }

    public static float easeOutQuad(final float progress, final float start, final float change, final float duration) {
        float t = progress / duration;
        return -change * t * (t - 2.0F) + start;
    }

    public static float easeInOutQuad(final float progress, final float start, final float change, final float duration) {
        float t = progress / (duration / 2.0F);
        if (t < 1.0F) {
            return change / 2.0F * t * t + start;
        }

        t--;
        return -change / 2.0F * (t * (t - 2.0F) - 1.0F) + start;
    }

    public static float easeInCubic(final float progress, final float start, final float change, final float duration) {
        float t = progress / duration;
        return change * t * t * t + start;
    }

    public static float easeOutCubic(final float progress, final float start, final float change, final float duration) {
        float t = progress / duration - 1.0F;
        return change * (t * t * t + 1.0F) + start;
    }

    public static float easeInOutCubic(final float progress, final float start, final float change, final float duration) {
        float t = progress / (duration / 2.0F);
        if (t < 1.0F) {
            return change / 2.0F * t * t * t + start;
        }

        t -= 2.0F;
        return change / 2.0F * (t * t * t + 2.0F) + start;
    }

    public static float easeInBack(final float progress, final float start, final float change, final float duration) {
        return easeInBack(progress, start, change, duration, DEFAULT_OVERSHOOT);
    }

    public static float easeInBack(final float progress, final float start, final float change, final float duration, final float overshoot) {
        float t = progress / duration;
        return change * t * t * ((overshoot + 1.0F) * t - overshoot) + start;
    }

    public static float easeOutBack(final float progress, final float start, final float change, final float duration) {
        return easeOutBack(progress, start, change, duration, DEFAULT_OVERSHOOT);
    }

    public static float easeOutBack(final float progress, final float start, final float change, final float duration, final float overshoot) {
        float t = progress / duration - 1.0F;
        return change * (t * t * ((overshoot + 1.0F) * t + overshoot) + 1.0F) + start;
    }

    public static float easeInOutBack(final float progress, final float start, final float change, final float duration) {
        return easeInOutBack(progress, start, change, duration, DEFAULT_OVERSHOOT);
    }

    public static float easeInOutBack(final float progress, final float start, final float change, final float duration, final float overshoot) {
        float scaledOvershoot = overshoot * 1.525F;
        float t = progress / (duration / 2.0F);
        if (t < 1.0F) {
            return change / 2.0F * t * t * ((scaledOvershoot + 1.0F) * t - scaledOvershoot) + start;
        }

        t -= 2.0F;
        return change / 2.0F * (t * t * ((scaledOvershoot + 1.0F) * t + scaledOvershoot) + 2.0F) + start;
    }
}
