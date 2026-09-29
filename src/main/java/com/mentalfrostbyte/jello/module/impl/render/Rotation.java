package com.mentalfrostbyte.jello.module.impl.render;

import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
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
 * the way the player walks and is kept within reach of the head. Switching on starts from the player's own rotation, so
 * if the server holds a different look at that moment the model shows it only from the next movement packet that
 * carries one.</p>
 *
 * <p>The vanilla renderer asks {@link #applyTo} for the local player's render state; that is the only place the game
 * touches this module.</p>
 */
public class Rotation extends Module {

    /** LivingEntity.tickHeadTurn: how much of the way to the walking direction the body turns in a tick. */
    private static final float BODY_EASE = 0.3F;

    // Game thread. The head's yaw and pitch this tick and the last, the yaw and pitch the last packet carried, and the
    // body's yaw this tick and the last; the model between two ticks is a blend of the pairs, as vanilla's is.
    private @Nullable LocalPlayer owner;
    private float sentYaw;
    private float sentPitch;
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

    /**
     * The look on the wire. Movement packets are sent from the game thread; one from anywhere else is not this player's
     * tick and is left alone, as {@code KillAura} does.
     */
    @EventTarget
    public void onSend(final EventSendPacket event) {
        if (!mc.isSameThread() || this.owner == null || this.owner != mc.player) {
            return;
        }
        if (event.getPacket() instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            this.sentYaw = move.getYRot(this.sentYaw);
            this.sentPitch = move.getXRot(this.sentPitch);
        }
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
        this.headYaw = this.sentYaw;
        this.pitch = this.sentPitch;
        this.bodyYaw = stepBody(this.bodyYaw, this.headYaw,
                player.getX() - player.xo, player.getZ() - player.zo,
                player.attackAnim > 0.0F, player.getMaxHeadRotationRelativeToBody());
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
        this.sentYaw = this.headYaw = this.headYawO = player.getYRot();
        this.sentPitch = this.pitch = this.pitchO = player.getXRot();
        this.bodyYaw = this.bodyYawO = player.yBodyRot;
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
        float target = bodyYaw;
        if ((float) (dx * dx + dz * dz) > 0.0025000002F) {
            float walkDirection = (float) Mth.atan2(dz, dx) * (180.0F / (float) Math.PI) - 90.0F;
            float off = Mth.abs(Mth.wrapDegrees(headYaw) - walkDirection);
            target = 95.0F < off && off < 265.0F ? walkDirection - 180.0F : walkDirection;
        }
        if (swinging) {
            target = headYaw;
        }

        float body = bodyYaw + Mth.wrapDegrees(target - bodyYaw) * BODY_EASE;
        float headDiff = Mth.wrapDegrees(headYaw - body);
        if (Math.abs(headDiff) > maxHeadTurn) {
            body += headDiff - Mth.sign(headDiff) * maxHeadTurn;
        }
        return body;
    }

    /**
     * The pitch between two ticks, kept to what a head can do: a {@code Derp} with {@code Safe Pitch} off reports
     * pitches past straight up and straight down, which the model would fold over itself for.
     */
    static float shownPitch(final float previous, final float current, final float partialTicks) {
        return Mth.clamp(Mth.lerp(partialTicks, previous, current), -90.0F, 90.0F);
    }
}
