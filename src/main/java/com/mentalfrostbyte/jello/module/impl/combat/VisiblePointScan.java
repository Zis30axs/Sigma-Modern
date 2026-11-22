package com.mentalfrostbyte.jello.module.impl.combat;

import java.util.function.Function;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Bounded samples on the three near faces of one box. All remembered points are box-relative. */
final class VisiblePointScan {
    static final int BUDGET = 24;
    private static final double EDGE = 0.001;
    private static final double[] GRID = {0.5, EDGE, 1 - EDGE, 0.25, 0.75, 0.125, 0.875, 0.375, 0.625};
    private static final int PERIOD = 3 * GRID.length * GRID.length;

    enum Kind { TARGET, BLOCK, OTHER_ENTITY, MISS, OUT_OF_RANGE }
    record Pick(Kind kind, String first, double distance) {}
    record Result(String source, @Nullable Vec3 point, Pick center, int samples, int nextSample) {}

    private int cursor;
    private boolean trySurfaceFirst;
    private @Nullable Vec3 previous;

    Result find(Vec3 eye, AABB box, Pick camera, Function<Vec3, Pick> pickAt) {
        Vec3 center = box.getCenter();
        Pick middle = pickAt.apply(center);
        if (camera.kind() == Kind.TARGET) return new Result("camera", null, middle, 0, cursor);
        if (previous != null) {
            Vec3 point = pointIn(box, previous.x, previous.y, previous.z);
            if (pickAt.apply(point).kind() == Kind.TARGET) return new Result("previous", point, middle, 0, cursor);
            previous = null;
        }
        boolean skipCenter = trySurfaceFirst;
        trySurfaceFirst = false;
        if (!skipCenter && middle.kind() == Kind.TARGET) return new Result("center", center, middle, 0, cursor);

        double nearX = eye.x < center.x ? EDGE : 1 - EDGE;
        double nearY = eye.y < center.y ? EDGE : 1 - EDGE;
        double nearZ = eye.z < center.z ? EDGE : 1 - EDGE;
        for (int n = 1; n <= BUDGET; n++) {
            int index = cursor;
            cursor = (cursor + 1) % PERIOD;
            int cell = index / 3;
            double u = GRID[cell % GRID.length];
            double v = GRID[cell / GRID.length];
            Vec3 point = switch (index % 3) {
                case 0 -> pointIn(box, u, v, nearZ);
                case 1 -> pointIn(box, nearX, v, u);
                default -> pointIn(box, u, nearY, v);
            };
            if (pickAt.apply(point).kind() == Kind.TARGET) return new Result("surface", point, middle, n, cursor);
        }
        return new Result("no_sample", null, middle, BUDGET, cursor);
    }

    void remember(AABB box, Vec3 hit) {
        trySurfaceFirst = false;
        previous = new Vec3(fraction(hit.x, box.minX, box.getXsize()), fraction(hit.y, box.minY, box.getYsize()),
                fraction(hit.z, box.minZ, box.getZsize()));
    }

    /** A held item's native pick can disagree with a geometric candidate. Try a different point next tick. */
    void rejected() {
        previous = null;
        trySurfaceFirst = true;
    }

    private static double fraction(double value, double min, double size) {
        return size > 0 ? Mth.clamp((value - min) / size, 0.0, 1.0) : 0.5;
    }

    private static Vec3 pointIn(AABB box, double x, double y, double z) {
        return new Vec3(box.minX + x * box.getXsize(), box.minY + y * box.getYsize(), box.minZ + z * box.getZsize());
    }

    void reset() {
        cursor = 0;
        previous = null;
        trySurfaceFirst = false;
    }
}
