package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.util.math.ReportedRotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Shows the look the server was told about on your own model, instead of the one your camera has.
 *
 * <p>A silent look ({@code KillAura}'s {@code Silent}, {@code Derp}) is only ever reported: the camera stays where the
 * player put it, and so does the model, so the head on screen says nothing about what other players see. This turns the
 * head, the pitch and the body of your model to the rotation carried by the last movement packet that had one, as the
 * other clients would show it. It only looks: nothing is sent and no rotation of the player changes, so the camera, the
 * aim and the movement stay as they were. It is visible in third person.</p>
 *
 * <p>The reported rotation is taken from the packets themselves, not from a module, so it is whatever anything at all
 * made the client say - a teleport confirmation included. The body is not reported; it is worked out the way a remote
 * client works it out for a player it only knows the head of ({@code LivingEntity.tickHeadTurn}): it drifts towards
 * the way the player walks and is kept within reach of the head. The local player's shared reported-look state is
 * tracked even while this cosmetic module is off, and ignores cancelled or replaced-away movement packets.</p>
 *
 * <p>The vanilla renderer asks {@link #applyTo} for the local player's render state; that is the only place the game
 * touches this module.</p>
 */
public class Rotation extends Module {

    // Game thread. The head's yaw and pitch this tick and the last, and the
    // body's yaw this tick and the last; the model between two ticks is a blend of the pairs, as vanilla's is.
    private @Nullable LocalPlayer owner;
    private float headYaw;
    private float headYawO;
    private float pitch;
    private float pitchO;
    private float bodyYaw;
    private float bodyYawO;

    public Rotation() {
        super(ModuleCategory.RENDER, "Rotation", "Shows the look the server was told about on your own model, e.g. a silent KillAura's");
    }

    @Override
    protected void onEnable() {
        this.reset(mc.player);
    }

    @Override
    protected void onDisable() {
        this.owner = null;
    }

    /** Once a tick, after the player's own tick has reported: the model moves on to what the server now holds. */
    @EventTarget
    public void onTick(final EventTick event) {
        if (!event.isPost()) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null) {
            this.owner = null;
            return;
        }
        if (player != this.owner) {
            this.reset(player);
            return;
        }

        this.headYawO = this.headYaw;
        this.pitchO = this.pitch;
        this.bodyYawO = this.bodyYaw;
        ReportedRotations.Sample reported = player.getReportedRotations().sample(1.0F,
                player.getYRot(), player.getXRot(), player.yBodyRot);
        this.headYaw = reported.yaw();
        this.pitch = reported.pitch();
        this.bodyYaw = reported.bodyYaw();
    }

    /**
     * Puts the reported look on {@code state}, the way vanilla would have from the entity's own: the yaw of the body,
     * the head's yaw relative to it and the pitch. Left as vanilla drew it when this player is not one the module is
     * following yet, and while the player is doing something that sets the model's facing itself.
     */
    public void applyTo(final LocalPlayer entity, final LivingEntityRenderState state, final float partialTicks) {
        if (entity != this.owner || entity.isPassenger() || entity.isSleeping() || !entity.isAlive()) {
            return;
        }
        float head = Mth.rotLerp(partialTicks, this.headYawO, this.headYaw);
        state.bodyRot = Mth.rotLerp(partialTicks, this.bodyYawO, this.bodyYaw);
        state.yRot = Mth.wrapDegrees(head - state.bodyRot);
        state.xRot = shownPitch(this.pitchO, this.pitch, partialTicks);
    }

    private void reset(final @Nullable LocalPlayer player) {
        this.owner = player;
        if (player == null) {
            return;
        }
        ReportedRotations.Sample reported = player.getReportedRotations().sample(1.0F,
                player.getYRot(), player.getXRot(), player.yBodyRot);
        this.headYaw = this.headYawO = reported.yaw();
        this.pitch = this.pitchO = reported.pitch();
        this.bodyYaw = this.bodyYawO = reported.bodyYaw();
    }

    /**
     * The body's yaw after one tick of a player whose head is at {@code headYaw}: {@code LivingEntity.tick}'s
     * choice of the yaw to turn to (the way the player moved, or the head's while swinging) followed by
     * {@code tickHeadTurn} (ease towards it, then stay within {@code maxHeadTurn} of the head), run with the reported
     * yaw in place of the entity's own.
     *
     * @param dx how far the player moved along x this tick
     * @param dz how far the player moved along z this tick
     */
    static float stepBody(final float bodyYaw, final float headYaw, final double dx, final double dz,
                          final boolean swinging, final float maxHeadTurn) {
        return ReportedRotations.stepBody(bodyYaw, headYaw, dx, dz, swinging, maxHeadTurn);
    }

    /**
     * The pitch between two ticks, kept to what a head can do: a {@code Derp} with {@code Safe Pitch} off reports
     * pitches past straight up and straight down, which the model would fold over itself for.
     */
    static float shownPitch(final float previous, final float current, final float partialTicks) {
        return Mth.clamp(Mth.lerp(partialTicks, previous, current), -90.0F, 90.0F);
    }
}
