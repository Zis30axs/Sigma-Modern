package com.mentalfrostbyte.jello.util.math;

import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The look in this player's last outgoing movement packet, after packet cancellation and replacement.
 * Owned by one LocalPlayer, so respawns cannot inherit another player's reported look. No entities are retained.
 */
public final class ReportedRotations {

    /** Head yaw/pitch are current packet values; body yaw is a visual estimate blended between ticks. */
    public record Sample(float yaw, float pitch, float bodyYaw) {}

    private record Look(float yaw, float pitch) {}

    // A network-thread packet may replace this pair; readers must never observe half of a rotation.
    private volatile @Nullable Look reported;
    // Body animation is updated and sampled only on the game thread.
    private boolean bodyInitialized;
    private float bodyYaw;
    private float bodyYawO;

    /** Called only for a movement packet that carries rotation and has reached the connection's send path. */
    public void record(final float yaw, final float pitch) {
        if (Float.isFinite(yaw) && Float.isFinite(pitch)) {
            this.reported = new Look(yaw, pitch);
        }
    }

    /**
     * Advances the inferred body once, after the local player's tick and its POST listeners. The body is not sent
     * over the network: vanilla eases it towards movement and keeps it within reach of the reported head.
     * Poses that control their own facing use the player's body instead.
     */
    public void tick(final float fallbackYaw, final float fallbackBodyYaw, final double dx, final double dz,
                     final boolean swinging, final float maxHeadTurn, final boolean poseControlsBody) {
        float fallbackBody = finiteOr(fallbackBodyYaw, finiteOr(fallbackYaw, 0.0F));
        Look look = this.reported;
        if (!this.bodyInitialized || look == null || poseControlsBody) {
            this.bodyYawO = this.bodyYaw = fallbackBody;
            this.bodyInitialized = true;
            return;
        }
        this.bodyYawO = this.bodyYaw;
        if (Double.isFinite(dx) && Double.isFinite(dz) && Float.isFinite(maxHeadTurn)) {
            float nextBody = stepBody(this.bodyYaw, look.yaw(), dx, dz, swinging, Math.max(0.0F, maxHeadTurn));
            if (Float.isFinite(nextBody)) {
                this.bodyYaw = nextBody;
            }
        }
    }

    /** The last reported head is immediate; only the estimated body is interpolated. */
    public Sample sample(final float partialTicks, final float fallbackYaw, final float fallbackPitch,
                         final float fallbackBodyYaw) {
        Look look = this.reported;
        float yaw = look == null ? finiteOr(fallbackYaw, 0.0F) : look.yaw();
        float pitch = look == null ? finiteOr(fallbackPitch, 0.0F) : look.pitch();
        float partial = Float.isFinite(partialTicks) ? Mth.clamp(partialTicks, 0.0F, 1.0F) : 1.0F;
        float body = this.bodyInitialized
                ? Mth.rotLerp(partial, this.bodyYawO, this.bodyYaw)
                : finiteOr(fallbackBodyYaw, yaw);
        return new Sample(yaw, pitch, body);
    }

    /** LivingEntity's movement/swing body target followed by its tickHeadTurn constraint. */
    public static float stepBody(final float bodyYaw, final float headYaw, final double dx, final double dz,
                                 final boolean swinging, final float maxHeadTurn) {
        float target = bodyYaw;
        if ((float) (dx * dx + dz * dz) > 0.0025000002F) {
            float walkDirection = (float) Mth.atan2(dz, dx) * (180.0F / (float) Math.PI) - 90.0F;
            float off = Mth.abs(Mth.wrapDegrees(headYaw) - walkDirection);
            target = 95.0F < off && off < 265.0F ? walkDirection - 180.0F : walkDirection;
        }
        if (swinging) {
            target = headYaw;
        }

        float body = bodyYaw + Mth.wrapDegrees(target - bodyYaw) * 0.3F;
        float headDiff = Mth.wrapDegrees(headYaw - body);
        if (Math.abs(headDiff) > maxHeadTurn) {
            body += headDiff - Mth.sign(headDiff) * maxHeadTurn;
        }
        return body;
    }

    private static float finiteOr(final float value, final float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }
}
