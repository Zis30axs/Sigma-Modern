package com.mentalfrostbyte.jello.gui.modern;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Current extraction's world projection, shared by follow-target HUDs independently of NameTags. */
public final class ModernHudProjection {
    private static final double MIN_W = 1.0E-7;
    private static final Matrix4f WORLD_TO_CLIP = new Matrix4f();
    private static Vec3 cameraPos = Vec3.ZERO;
    private static boolean available;

    private ModernHudProjection() {}

    public record Segment(float x1, float y1, float x2, float y2) {}

    /** The input already includes bob/hurt/portal effects and uses zero-to-one clip depth. */
    public static void beginFrame(@Nullable CameraRenderState camera, @Nullable Matrix4fc worldToClip) {
        available = camera != null && worldToClip != null && camera.initialized
                && !camera.isPanoramicMode && !camera.isFrustumCaptured;
        if (available) {
            cameraPos = camera.pos;
            WORLD_TO_CLIP.set(worldToClip);
        } else {
            cameraPos = Vec3.ZERO;
        }
    }

    public static boolean available() {
        return available;
    }

    public static NameTagProjection.@Nullable Point project(Vec3 worldPosition, int guiWidth, int guiHeight) {
        if (!available) return null;
        return NameTagProjection.project(WORLD_TO_CLIP, worldPosition.x - cameraPos.x,
                worldPosition.y - cameraPos.y, worldPosition.z - cameraPos.z, guiWidth, guiHeight);
    }

    public static EspBoxProjection.@Nullable Bounds projectBox(AABB worldBox, int guiWidth, int guiHeight) {
        return available ? EspBoxProjection.project(WORLD_TO_CLIP, worldBox, cameraPos, guiWidth, guiHeight) : null;
    }

    /** Clips before perspective division so lines entering the view or crossing the near plane stay continuous. */
    public static @Nullable Segment projectSegment(Vec3 from, Vec3 to, int guiWidth, int guiHeight) {
        if (!available || guiWidth <= 0 || guiHeight <= 0) return null;
        double x1 = from.x - cameraPos.x, y1 = from.y - cameraPos.y, z1 = from.z - cameraPos.z;
        double x2 = to.x - cameraPos.x, y2 = to.y - cameraPos.y, z2 = to.z - cameraPos.z;
        double ax = WORLD_TO_CLIP.m00() * x1 + WORLD_TO_CLIP.m10() * y1 + WORLD_TO_CLIP.m20() * z1 + WORLD_TO_CLIP.m30();
        double ay = WORLD_TO_CLIP.m01() * x1 + WORLD_TO_CLIP.m11() * y1 + WORLD_TO_CLIP.m21() * z1 + WORLD_TO_CLIP.m31();
        double az = WORLD_TO_CLIP.m02() * x1 + WORLD_TO_CLIP.m12() * y1 + WORLD_TO_CLIP.m22() * z1 + WORLD_TO_CLIP.m32();
        double aw = WORLD_TO_CLIP.m03() * x1 + WORLD_TO_CLIP.m13() * y1 + WORLD_TO_CLIP.m23() * z1 + WORLD_TO_CLIP.m33();
        double bx = WORLD_TO_CLIP.m00() * x2 + WORLD_TO_CLIP.m10() * y2 + WORLD_TO_CLIP.m20() * z2 + WORLD_TO_CLIP.m30();
        double by = WORLD_TO_CLIP.m01() * x2 + WORLD_TO_CLIP.m11() * y2 + WORLD_TO_CLIP.m21() * z2 + WORLD_TO_CLIP.m31();
        double bz = WORLD_TO_CLIP.m02() * x2 + WORLD_TO_CLIP.m12() * y2 + WORLD_TO_CLIP.m22() * z2 + WORLD_TO_CLIP.m32();
        double bw = WORLD_TO_CLIP.m03() * x2 + WORLD_TO_CLIP.m13() * y2 + WORLD_TO_CLIP.m23() * z2 + WORLD_TO_CLIP.m33();
        if (!finite(ax, ay, az, aw) || !finite(bx, by, bz, bw)) return null;

        double first = 0.0, last = 1.0;
        for (int plane = 0; plane < 7; plane++) {
            double a = distance(ax, ay, az, aw, plane), b = distance(bx, by, bz, bw, plane);
            if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0.0 && b < 0.0) return null;
            if ((a < 0.0) != (b < 0.0)) {
                double fraction = a / (a - b);
                if (a < 0.0) first = Math.max(first, fraction);
                else last = Math.min(last, fraction);
                if (first > last) return null;
            }
        }
        double startW = aw * (1.0 - first) + bw * first;
        double endW = aw * (1.0 - last) + bw * last;
        if (startW < MIN_W * 0.99 || endW < MIN_W * 0.99) return null;
        float sx = (float) (Math.clamp(((ax * (1.0 - first) + bx * first) / startW + 1.0) * 0.5, 0.0, 1.0) * guiWidth);
        float sy = (float) (Math.clamp((1.0 - (ay * (1.0 - first) + by * first) / startW) * 0.5, 0.0, 1.0) * guiHeight);
        float ex = (float) (Math.clamp(((ax * (1.0 - last) + bx * last) / endW + 1.0) * 0.5, 0.0, 1.0) * guiWidth);
        float ey = (float) (Math.clamp((1.0 - (ay * (1.0 - last) + by * last) / endW) * 0.5, 0.0, 1.0) * guiHeight);
        return Float.isFinite(sx) && Float.isFinite(sy) && Float.isFinite(ex) && Float.isFinite(ey)
                ? new Segment(sx, sy, ex, ey) : null;
    }

    private static double distance(double x, double y, double z, double w, int plane) {
        return switch (plane) {
            case 0 -> w + x;
            case 1 -> w - x;
            case 2 -> w + y;
            case 3 -> w - y;
            case 4 -> z;
            case 5 -> w - z;
            default -> w - MIN_W;
        };
    }

    private static boolean finite(double x, double y, double z, double w) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Double.isFinite(w);
    }
}
