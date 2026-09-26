package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/** First-person poses for an actual sword block action supplied by ViaFabricPlus. */
public class BlockAnimation extends Module {

    private final EnumSetting<Mode> mode = this.register(new EnumSetting<>(
            "Mode", "First-person sword blocking style.", Mode.ONE_SEVEN));

    private float progress;
    private long lastFrameNanos;
    private AbstractClientPlayer lastPlayer;

    public BlockAnimation() {
        super(ModuleCategory.RENDER, "BlockAnimation", "Changes the first-person sword blocking animation");
    }

    /** Called only for a main-hand sword after the vanilla arm placement. */
    public boolean apply(final PoseStack poseStack, final AbstractClientPlayer player, final HumanoidArm arm,
                         final float swing, final boolean blocking) {
        if (this.lastPlayer != player) {
            this.reset();
            this.lastPlayer = player;
        }
        long now = System.nanoTime();
        float elapsed = this.lastFrameNanos == 0L ? 1.0F / 60.0F
            : Mth.clamp((now - this.lastFrameNanos) / 1_000_000_000.0F, 0.0F, 0.05F);
        this.lastFrameNanos = now;
        this.progress = Mth.clamp(this.progress + (blocking ? 1.0F : -1.0F) * elapsed * 8.0F, 0.0F, 1.0F);
        if (this.progress == 0.0F) {
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
}
