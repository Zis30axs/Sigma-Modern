package com.mentalfrostbyte.jello.util.math;

/**
 * Cubic-bezier interpolation compatible with the four control values used throughout Sigma's old UI.
 *
 * <p>The legacy helper generated roughly 180 temporary points for every call and then linearly searched
 * them. This implementation solves the same cubic curve directly and allocates nothing, so old animation
 * timings can be preserved without carrying the old per-frame allocation cost into 26.2.</p>
 */
public final class SmoothInterpolator {

    private SmoothInterpolator() {
    }

    public static float interpolate(final float progress, final double... points) {
        if (points.length != 4) {
            throw new IllegalArgumentException("Expected exactly four cubic-bezier control values");
        }
        return interpolate(progress, points[0], points[1], points[2], points[3]);
    }

    public static float interpolate(
        final float progress,
        final double x1,
        final double y1,
        final double x2,
        final double y2
    ) {
        final double targetX = clamp01(progress);
        if (targetX <= 0.0) {
            return 0.0F;
        }
        if (targetX >= 1.0) {
            return 1.0F;
        }

        double low = 0.0;
        double high = 1.0;
        double t = targetX;
        for (int i = 0; i < 18; i++) {
            final double x = cubic(t, 0.0, x1, x2, 1.0);
            if (x < targetX) {
                low = t;
            } else {
                high = t;
            }
            t = (low + high) * 0.5;
        }

        return (float) cubic(t, 0.0, y1, y2, 1.0);
    }

    private static double cubic(final double t, final double p0, final double p1, final double p2, final double p3) {
        final double oneMinusT = 1.0 - t;
        return oneMinusT * oneMinusT * oneMinusT * p0
            + 3.0 * oneMinusT * oneMinusT * t * p1
            + 3.0 * oneMinusT * t * t * p2
            + t * t * t * p3;
    }

    private static double clamp01(final double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
