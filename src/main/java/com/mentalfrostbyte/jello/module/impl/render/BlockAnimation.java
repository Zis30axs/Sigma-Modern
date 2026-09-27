package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Quaternionf;

/** First-person poses for an actual sword block action supplied by ViaFabricPlus. */
public class BlockAnimation extends Module {

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>(
            "Mode", "First-person sword blocking style.", Mode.ONE_SEVEN));
    private final EnumSetting<Transition> transition = this.register(new EnumSetting<>(
            "Transition", "Old switches poses immediately; Smooth eases between them.", Transition.SMOOTH));

    private float progress;
    private long lastFrameNanos;
    private AbstractClientPlayer lastPlayer;

    public BlockAnimation() {
        super(ModuleCategory.RENDER, "BlockAnimation", "Changes the first-person sword blocking animation");
    }

    /**
     * Called only for a main-hand sword after the vanilla arm placement. Returns whether this method
     * supplied the complete block pose; Old's LiquidBounce styles return false so the caller adds vanilla's
     * BLOCK transform after their extra movement.
     */
    public boolean apply(final PoseStack poseStack, final AbstractClientPlayer player, final HumanoidArm arm,
                         final float swing, final boolean blocking) {
        if (this.lastPlayer != player) {
            this.reset();
            this.lastPlayer = player;
        }
        boolean old = this.transition.is(Transition.OLD);
        if (old) {
            this.progress = blocking ? 1.0F : 0.0F;
            this.lastFrameNanos = 0L;
        } else {
            long now = System.nanoTime();
            float elapsed = this.lastFrameNanos == 0L ? 1.0F / 60.0F
                : Mth.clamp((now - this.lastFrameNanos) / 1_000_000_000.0F, 0.0F, 0.05F);
            this.lastFrameNanos = now;
            this.progress = Mth.clamp(this.progress + (blocking ? 1.0F : -1.0F) * elapsed * 8.0F, 0.0F, 1.0F);
        }
        if (this.progress == 0.0F) {
            return false;
        }

        if (old && this.applyOld(poseStack, arm, swing)) {
            // LiquidBounce's six styles run after the arm transform and before vanilla's BLOCK pose.
            return false;
        }

        float eased = this.progress * this.progress * (3.0F - 2.0F * this.progress);
        float arc = Mth.sin(Mth.sqrt(Mth.clamp(swing, 0.0F, 1.0F)) * (float) Math.PI);
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        switch (this.mode.get()) {
            // The first six poses are adapted from LiquidBounce's named blocking styles.
            case ONE_SEVEN -> {
                poseStack.translate(-side * 0.14F * eased, 0.08F * eased, 0.14F * eased);
                rotate(poseStack, eased, -102.25F, side * 13.365F, side * 78.05F);
                poseStack.mulPose(Axis.XP.rotationDegrees(-arc * 12.0F * eased));
            }
            case PUSHDOWN -> {
                poseStack.translate(-side * 0.10F * eased, (-0.10F - arc * 0.12F) * eased, 0.11F * eased);
                rotate(poseStack, eased, -78.0F - arc * 34.0F, side * 18.0F, side * (60.0F + arc * 9.0F));
            }
            case SIGMA -> {
                poseStack.translate(-side * 0.08F * eased, 0.17F * eased, 0.03F * eased);
                rotate(poseStack, eased, -88.0F - arc * 27.5F, -side * (11.0F + arc * 20.0F), side * 91.0F);
            }
            case EXHIBITION -> {
                poseStack.translate(side * 0.09F * eased, 0.01F * eased, -0.09F * eased);
                rotate(poseStack, eased, -112.0F - arc * 24.0F, side * (42.0F + arc * 16.0F), side * 55.0F);
            }
            case AVATAR -> {
                poseStack.translate(side * 0.19F * eased, 0.15F * eased, -0.04F * eased);
                rotate(poseStack, eased, -64.0F - arc * 39.0F, -side * (34.0F + arc * 16.0F), side * (106.0F - arc * 18.0F));
            }
            case DORTWARE -> {
                poseStack.translate(-side * 0.23F * eased, 0.02F * eased, -0.17F * eased);
                rotate(poseStack, eased, -124.0F + arc * 18.0F, side * 64.0F, side * (36.0F + arc * 19.0F));
            }
            // FDPClient's element modes inspired these four different swing trajectories.
            case HELIUM -> {
                poseStack.translate(-side * 0.04F * eased, (0.25F - arc * 0.06F) * eased, 0.10F * eased);
                rotate(poseStack, eased, -98.0F - arc * 48.0F, side * 6.0F, side * 55.0F);
            }
            case ARGON -> {
                poseStack.translate(side * (0.12F + arc * 0.08F) * eased, (-0.04F + arc * 0.10F) * eased, 0.17F * eased);
                rotate(poseStack, eased, -76.0F, side * (57.0F + arc * 32.0F), side * (65.0F - arc * 16.0F));
            }
            case CESIUM -> {
                poseStack.translate(-side * 0.11F * eased, 0.13F * eased, (0.20F - arc * 0.07F) * eased);
                rotate(poseStack, eased, -58.0F - arc * 12.0F, -side * (43.0F + arc * 28.0F), side * 102.0F);
            }
            case SULFUR -> {
                poseStack.translate(side * (0.02F + arc * 0.18F) * eased, 0.05F * eased, -0.21F * eased);
                rotate(poseStack, eased, -117.0F - arc * 21.0F, side * arc * 25.0F, side * 70.0F);
            }
        }
        return true;
    }

    /** LiquidBounce NextGen's blocking styles, applied before the vanilla sword BLOCK transform. */
    private boolean applyOld(final PoseStack poseStack, final HumanoidArm arm, final float swing) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float swingProgress = Mth.clamp(swing, 0.0F, 1.0F);
        float sine = Mth.sin(Mth.sqrt(swingProgress) * (float) Math.PI);
        switch (this.mode.get()) {
            case ONE_SEVEN -> {
                poseStack.translate(-side * 0.1F, 0.1F, 0.0F);
                applySwingOffset(poseStack, side, swingProgress * 0.9F);
            }
            case PUSHDOWN -> {
                poseStack.translate(-side * 0.1F, 0.1F, 0.0F);
                poseStack.mulPose(Axis.ZP.rotationDegrees(side * sine * 10.0F));
                poseStack.mulPose(Axis.XP.rotationDegrees(-sine * 35.0F));
            }
            case SIGMA -> {
                poseStack.mulPose(new Quaternionf().rotationAxis(radians(-sine * 27.5F * side),
                    -8.0F * side, 0.0F, 9.0F).rotateAxis(radians(-sine * 45.0F * side),
                    side, sine / 2.0F, 0.0F));
                poseStack.translate(0.0F, 0.1F, 0.0F);
            }
            case EXHIBITION -> {
                poseStack.translate(0.0F, -0.1F, 0.0F);
                poseStack.translate(0.1F, 0.4F, -0.1F);
                poseStack.mulPose(new Quaternionf().rotationAxis(radians(-sine * 30.0F * side),
                    sine / 2.0F, 0.0F, 9.0F).rotateAxis(radians(-sine * 50.0F * side),
                    0.8F * side, sine / 2.0F, 0.0F));
                poseStack.translate(0.0F, -0.1F, 0.0F);
            }
            case AVATAR -> {
                float sineSquared = Mth.sin(swingProgress * swingProgress * (float) Math.PI);
                poseStack.translate(0.2F * side, 0.1F, 0.0F);
                poseStack.mulPose(Axis.YP.rotationDegrees(-sineSquared * 20.0F * side));
                poseStack.mulPose(Axis.ZP.rotationDegrees(-sine * 20.0F * side));
                poseStack.mulPose(Axis.XP.rotationDegrees(-sine * 40.0F));
            }
            case DORTWARE -> {
                float alternateSine = Mth.sin(Mth.sqrt(swingProgress) * (float) Math.PI - 3.0F);
                poseStack.mulPose(new Quaternionf().rotationAxis(radians(-sine * 10.0F),
                    0.0F, 15.0F, 200.0F).rotateAxis(radians(-sine * 10.0F),
                    300.0F, sine / 2.0F, 1.0F));
                poseStack.translate(3.4F, 0.3F, -0.4F);
                poseStack.translate(-2.1F, -0.2F, 0.1F);
                poseStack.mulPose(new Quaternionf().rotationAxis(radians(alternateSine * 13.0F),
                    -10.0F, -1.4F, -10.0F));
                poseStack.translate(arm == HumanoidArm.RIGHT ? -1.0F : -2.0F, 0.1F, 0.0F);
            }
            default -> {
                // The four FDPClient styles use the existing pose with an immediate transition.
                return false;
            }
        }
        return true;
    }

    private static void applySwingOffset(final PoseStack poseStack, final int side, final float swing) {
        float horizontal = Mth.sin(swing * swing * (float) Math.PI);
        float vertical = Mth.sin(Mth.sqrt(swing) * (float) Math.PI);
        poseStack.mulPose(Axis.YP.rotationDegrees(side * (45.0F - horizontal * 20.0F)));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-side * vertical * 20.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(-vertical * 80.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(-side * 45.0F));
    }

    private static float radians(final float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static void rotate(final PoseStack poseStack, final float progress, final float x, final float y, final float z) {
        poseStack.mulPose(Axis.XP.rotationDegrees(x * progress));
        poseStack.mulPose(Axis.YP.rotationDegrees(y * progress));
        poseStack.mulPose(Axis.ZP.rotationDegrees(z * progress));
    }

    /** Clears the pose when the sword is swapped away or vanilla starts using another item. */
    public void reset() {
        this.progress = 0.0F;
        this.lastFrameNanos = 0L;
        this.lastPlayer = null;
    }

    @Override
    protected void onDisable() {
        this.reset();
    }

    public enum Mode {
        ONE_SEVEN,
        PUSHDOWN,
        SIGMA,
        EXHIBITION,
        AVATAR,
        DORTWARE,
        HELIUM,
        ARGON,
        CESIUM,
        SULFUR
    }

    public enum Transition {
        OLD,
        SMOOTH
    }
}
