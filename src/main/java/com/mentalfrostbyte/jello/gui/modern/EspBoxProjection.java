package com.mentalfrostbyte.jello.gui.modern;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Clips box faces in homogeneous space before division, including boxes crossing the near plane. */
public final class EspBoxProjection {
    private static final double MIN_W = 1.0E-7;
    private static final int[][] FACES = {
            {0, 2, 6, 4}, {1, 5, 7, 3}, {0, 4, 5, 1},
            {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 6, 7, 5}
    };

    public record Bounds(float left, float top, float right, float bottom) {
        public float width() { return this.right - this.left; }
        public float height() { return this.bottom - this.top; }
    }

    private EspBoxProjection() {}

    /** The projection uses [0,w] depth, for either ordinary or reverse-Z projection matrices. */
    public static @Nullable Bounds project(Matrix4fc matrix, AABB box, Vec3 camera, int width, int height) {
        if (width <= 0 || height <= 0 || !finite(box.minX, box.minY, box.minZ)
                || !finite(box.maxX, box.maxY, box.maxZ) || !finite(camera.x, camera.y, camera.z)
                || box.minX >= box.maxX || box.minY >= box.maxY || box.minZ >= box.maxZ) return null;
        // A box enclosing/touching the eye has no useful screen outline; don't turn it into a full-screen frame.
        if (camera.x >= box.minX && camera.x <= box.maxX && camera.y >= box.minY && camera.y <= box.maxY
                && camera.z >= box.minZ && camera.z <= box.maxZ) return null;
        double[] corners = new double[32];
        boolean allInside = true;
        int commonOutside = 0x7F;
        for (int index = 0; index < 8; index++) {
            // Subtract camera in double precision before multiplying by the float matrix.
            double x = ((index & 1) == 0 ? box.minX : box.maxX) - camera.x;
            double y = ((index & 2) == 0 ? box.minY : box.maxY) - camera.y;
            double z = ((index & 4) == 0 ? box.minZ : box.maxZ) - camera.z;
            int offset = index * 4;
            corners[offset] = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
            corners[offset + 1] = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
            corners[offset + 2] = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
            corners[offset + 3] = matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33();
            if (!finite(corners[offset], corners[offset + 1], corners[offset + 2])
                    || !Double.isFinite(corners[offset + 3])) return null;
            int outside = 0;
            for (int plane = 0; plane < 7; plane++) {
                if (distance(corners, offset, plane) < 0.0) outside |= 1 << plane;
            }
            commonOutside &= outside;
            allInside &= outside == 0;
        }
        if (commonOutside != 0) return null;
        double[] extrema = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        if (allInside) {
            for (int offset = 0; offset < corners.length; offset += 4) accumulate(corners, offset, extrema);
        } else {
            // A clipped quad has at most eleven vertices after seven planes. Reuse buffers for all six faces.
            double[] first = new double[64];
            double[] second = new double[64];
            for (int[] face : FACES) {
                for (int index = 0; index < 4; index++) System.arraycopy(corners, face[index] * 4, first, index * 4, 4);
                double[] input = first;
                double[] output = second;
                int count = 4;
                for (int plane = 0; plane < 7 && count > 0; plane++) {
                    count = clip(input, count, output, plane);
                    double[] swap = input;
                    input = output;
                    output = swap;
                }
                for (int index = 0; index < count; index++) accumulate(input, index * 4, extrema);
            }
        }
        if (!Double.isFinite(extrema[0]) || !Double.isFinite(extrema[1])
                || !Double.isFinite(extrema[2]) || !Double.isFinite(extrema[3])) return null;
        float left = (float) (Math.clamp(extrema[0] * 0.5 + 0.5, 0.0, 1.0) * width);
        float top = (float) (Math.clamp(0.5 - extrema[3] * 0.5, 0.0, 1.0) * height);
        float right = (float) (Math.clamp(extrema[2] * 0.5 + 0.5, 0.0, 1.0) * width);
        float bottom = (float) (Math.clamp(0.5 - extrema[1] * 0.5, 0.0, 1.0) * height);
        return right > left && bottom > top ? new Bounds(left, top, right, bottom) : null;
    }

    private static int clip(double[] input, int count, double[] output, int plane) {
        int written = 0;
        int previous = (count - 1) * 4;
        double previousDistance = distance(input, previous, plane);
        for (int index = 0; index < count; index++) {
            int current = index * 4;
            double currentDistance = distance(input, current, plane);
            if ((previousDistance >= 0.0) != (currentDistance >= 0.0)) {
                double fraction = previousDistance / (previousDistance - currentDistance);
                for (int component = 0; component < 4; component++) {
                    // Convex combination also avoids overflow from subtracting widely separated endpoints.
                    output[written * 4 + component] = input[previous + component] * (1.0 - fraction)
                            + input[current + component] * fraction;
                }
                written++;
            }
            if (currentDistance >= 0.0) {
                System.arraycopy(input, current, output, written * 4, 4);
                written++;
            }
            previous = current;
            previousDistance = currentDistance;
        }
        return written;
    }

    private static double distance(double[] vertex, int offset, int plane) {
        return switch (plane) {
            case 0 -> vertex[offset + 3] + vertex[offset];
            case 1 -> vertex[offset + 3] - vertex[offset];
            case 2 -> vertex[offset + 3] + vertex[offset + 1];
            case 3 -> vertex[offset + 3] - vertex[offset + 1];
            case 4 -> vertex[offset + 2];
            case 5 -> vertex[offset + 3] - vertex[offset + 2];
            default -> vertex[offset + 3] - MIN_W;
        };
    }

    private static void accumulate(double[] vertex, int offset, double[] extrema) {
        if (vertex[offset + 3] < MIN_W * 0.99) return;
        double x = vertex[offset] / vertex[offset + 3];
        double y = vertex[offset + 1] / vertex[offset + 3];
        extrema[0] = Math.min(extrema[0], x);
        extrema[1] = Math.min(extrema[1], y);
        extrema[2] = Math.max(extrema[2], x);
        extrema[3] = Math.max(extrema[3], y);
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
