package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Replaces vanilla's sharp |cos| view bobbing with a smooth C-infinity stride wave,
 * sprint energy boost, subtle strafe roll, and soft landing cushion.
 */
public class SmoothBobbing extends Module {

    private final NumberSetting intensity = this.register(new NumberSetting(
            "Intensity", "Overall camera bobbing amplitude.", 1.0F, 0.1F, 2.0F, 0.05F));
    private final NumberSetting sprintBoost = this.register(new NumberSetting(
            "Sprint Boost", "Extra camera motion energy while sprinting.", 1.35F, 1.0F, 2.0F, 0.05F));
    private final NumberSetting roll = this.register(new NumberSetting(
            "Roll", "Side-tilt strength from footsteps and strafing.", 1.0F, 0.0F, 2.0F, 0.1F));
    private final NumberSetting smoothness = this.register(new NumberSetting(
            "Smoothness", "Damping speed. Higher feels snappier, lower feels floatier.", 12.0F, 4.0F, 24.0F, 1.0F));
    private final BooleanSetting affectHand = this.register(new BooleanSetting(
            "Affect Hand", "Apply smooth bobbing to the first-person hand as well.", true));

    private @Nullable CameraRenderState lastFrameState;
    private @Nullable LocalPlayer owner;
    private @Nullable Level ownerLevel;
    private long lastNanos;
    private float smoothBob;
    private float smoothSprint = 1.0F;
    private float smoothStrafe;
    private float landingDip;
    private boolean wasOnGround = true;
    private double lastVy;
    private float tx, ty, rollDeg, pitchDeg;

    public SmoothBobbing() {
        super(ModuleCategory.RENDER, "SmoothBobbing",
                "Smooth camera bobbing, sprint energy and strafe tilt when walking or running");
    }

    public boolean affectsHand() {
        return this.affectHand.get();
    }

    @Override
    protected void onDisable() {
        this.resetState();
    }

    @EventTarget
    public void onLoadWorld(final EventLoadWorld event) {
        this.resetState();
    }

    private void resetState() {
        this.lastFrameState = null;
        this.owner = null;
        this.ownerLevel = null;
        this.lastNanos = 0L;
        this.smoothBob = 0.0F;
        this.smoothSprint = 1.0F;
        this.smoothStrafe = 0.0F;
        this.landingDip = 0.0F;
        this.wasOnGround = true;
        this.lastVy = 0.0;
        this.tx = this.ty = this.rollDeg = this.pitchDeg = 0.0F;
    }

    /**
     * Advances motion once during render extraction. CameraRenderState is reused across frames;
     * its identity cannot tell whether a new frame has started.
     */
    public void beginFrame(final CameraRenderState cameraState, final @Nullable LocalPlayer player) {
        if (player == null || !cameraState.initialized || !cameraState.entityRenderState.isPlayer || !player.isAlive()) {
            this.resetState();
            return;
        }
        if (this.owner != player || this.ownerLevel != player.level()) {
            this.resetState();
        }
        this.owner = player;
        this.ownerLevel = player.level();
        this.lastFrameState = cameraState;
        this.step(cameraState, player);
    }

    /** World view and all hand passes apply the cached motion without advancing it again. */
    public void apply(final CameraRenderState cameraState, final LocalPlayer player, final PoseStack poseStack) {
        if (this.lastFrameState != cameraState || this.owner != player || this.ownerLevel != player.level()) {
            return;
        }
        poseStack.translate(this.tx, this.ty, 0.0F);
        if (Math.abs(this.rollDeg) > 1.0E-4F) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(this.rollDeg));
        }
        if (Math.abs(this.pitchDeg) > 1.0E-4F) {
            poseStack.mulPose(Axis.XP.rotationDegrees(this.pitchDeg));
        }
    }

    private void step(final CameraRenderState cameraState, final LocalPlayer player) {
        long now = System.nanoTime();
        float dt = this.lastNanos == 0L ? 0.016F : (now - this.lastNanos) / 1_000_000_000.0F;
        this.lastNanos = now;
        if (dt <= 0.0F || dt > 0.25F) {
            this.smoothBob = 0.0F;
            this.smoothSprint = 1.0F;
            this.smoothStrafe = 0.0F;
            this.landingDip = 0.0F;
            this.wasOnGround = player.onGround();
            this.lastVy = player.getDeltaMovement().y;
            dt = 0.016F;
        }

        float blend = 1.0F - (float) Math.exp(-dt * this.smoothness.get());
        boolean onGround = player.onGround();
        boolean walkingOnGround = onGround && !player.isSwimming() && !player.isFallFlying() && !player.isPassenger();

        if (!this.wasOnGround && onGround && this.lastVy < -0.18) {
            this.landingDip = Math.max(this.landingDip, Math.clamp((float) (-this.lastVy) * 0.075F, 0.0F, 0.085F));
        }
        this.wasOnGround = onGround;
        this.lastVy = player.getDeltaMovement().y;
        this.landingDip *= (float) Math.exp(-dt * 11.0F);

        float targetBob = walkingOnGround ? cameraState.entityRenderState.bob : 0.0F;
        float targetSprint = walkingOnGround && player.isSprinting() ? this.sprintBoost.get() : 1.0F;
        this.smoothBob += (targetBob - this.smoothBob) * blend;
        this.smoothSprint += (targetSprint - this.smoothSprint) * blend;

        double vx = player.getX() - player.xo;
        double vz = player.getZ() - player.zo;
        double rad = Math.toRadians(player.getYRot());
        float rightSpeed = (float) (-vx * Math.cos(rad) - vz * Math.sin(rad));
        float targetStrafe = walkingOnGround ? Math.clamp(rightSpeed * 5.5F, -1.0F, 1.0F) : 0.0F;
        this.smoothStrafe += (targetStrafe - this.smoothStrafe) * blend;

        float walk = cameraState.entityRenderState.backwardsInterpolatedWalkDistance;
        float amp = this.intensity.get();
        float energy = this.smoothBob * this.smoothSprint * amp;
        float sway = strideSway(walk);
        float lift = strideLift(walk);

        this.tx = sway * energy * 0.42F;
        this.ty = -(lift * energy * 0.68F + this.landingDip * amp);
        this.rollDeg = (sway * energy * 2.1F + this.smoothStrafe * 1.45F * amp) * this.roll.get();
        this.pitchDeg = (lift * energy * 2.8F + this.landingDip * 18.0F * amp);
    }

    /** Smooth sinusoidal left-right sway across each two-step cycle. */
    static float strideSway(final float walkDistance) {
        return Mth.sin(walkDistance * (float) Math.PI);
    }

    /** C-infinity raised-cosine footstep lift in [0, 1], replacing vanilla's sharp |cos| cusp. */
    static float strideLift(final float walkDistance) {
        return 0.5F - 0.5F * Mth.cos(walkDistance * (float) (Math.PI * 2.0));
    }
}
