package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.EventBus;
import com.mentalfrostbyte.jello.event.EventPriority;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import com.mentalfrostbyte.jello.util.movement.MovementCorrector;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.jspecify.annotations.Nullable;

/**
 * Keeps each aura attack followed by a movement report of the look it used, even while standing still.
 * Some servers finish checking a hit against the following report, so having sent the same look before
 * the attack does not finish that attack. Game thread only.
 */
final class AuraAttackCycle {

    private final DrainListener drainListener = new DrainListener();
    private @Nullable Rotation lastSent;
    private @Nullable Rotation unreportedAttack;
    private @Nullable Rotation preCamera;
    private @Nullable MovementCorrection drainCorrection;
    private boolean ownsSilentLook;
    private boolean drainingAttackLook;
    private boolean drainingOnBus;

    void reset(final @Nullable Rotation initial) {
        this.stopBusDrain();
        this.lastSent = initial;
        this.unreportedAttack = null;
        this.preCamera = null;
        this.ownsSilentLook = false;
        this.drainingAttackLook = false;
    }

    /**
     * Reclaims wire tracking when the owning module is re-enabled: if a background drain is still
     * finishing a previous hit or silent handback, keep its live wire state instead of wiping it.
     */
    void reclaim(final @Nullable Rotation initial) {
        boolean wasBusy = this.isBusy();
        this.stopBusDrain();
        if (!wasBusy) {
            this.reset(initial);
        }
    }

    @Nullable Rotation lastSent() {
        return this.lastSent;
    }

    @Nullable Rotation unreportedAttack() {
        return this.unreportedAttack;
    }

    boolean hasUnreportedAttack() {
        return this.unreportedAttack != null;
    }

    boolean isDrainingOnBus() {
        return this.drainingOnBus;
    }

    /** Another item action must wait until both the attack report and silent-look handback are complete. */
    boolean isBusy() {
        return this.unreportedAttack != null || this.ownsSilentLook;
    }

    /** Called after the attack has been sent; movement packets sent before it cannot finish this hit. */
    void attacked(final Rotation look, final boolean silent) {
        this.unreportedAttack = look;
        this.ownsSilentLook |= silent;
    }

    void motion(final EventMotion event, final @Nullable Rotation desired, final boolean silent) {
        if (event.isCancelled()) {
            return;
        }
        if (event.isPre()) {
            Rotation camera = new Rotation(event.getYaw(), event.getPitch());
            this.preCamera = camera;
            this.drainingAttackLook = false;
            if (desired != null && silent) {
                this.ownsSilentLook = true;
                event.setYaw(desired.yaw());
                event.setPitch(desired.pitch());
                if (!desired.equals(this.lastSent)) {
                    event.forceRotation();
                }
            } else if (desired == null && this.unreportedAttack != null) {
                // Finish reporting a queued hit or item use inside this tick's normal movement packet
                // before handing a silent look back to the camera on the following tick.
                this.drainingAttackLook = true;
                this.ownsSilentLook |= !camera.equals(this.unreportedAttack);
                event.setYaw(this.unreportedAttack.yaw());
                event.setPitch(this.unreportedAttack.pitch());
                event.forceRotation();
            } else if (this.ownsSilentLook) {
                if (!camera.equals(this.lastSent)) {
                    event.forceRotation();
                } else if (this.unreportedAttack == null) {
                    this.ownsSilentLook = false;
                }
            }
            if (this.unreportedAttack != null) {
                // A report before the attack, even with the same angles, cannot drain a queued hit.
                event.forceRotation();
            }
        } else if (event.isPost()) {
            Rotation camera = this.drainingAttackLook && this.preCamera != null
                    ? this.preCamera : new Rotation(event.getYaw(), event.getPitch());
            this.drainingAttackLook = false;
            this.preCamera = null;
            if (this.ownsSilentLook && this.unreportedAttack == null && !(desired != null && silent)
                    && camera.equals(this.lastSent)) {
                this.ownsSilentLook = false;
            }
        }
    }

    /** Only accepted movement packets are passed here; each one can finish hits against the current look. */
    void reported(final ServerboundMovePlayerPacket packet) {
        if (packet.hasRotation()) {
            this.lastSent = new Rotation(packet.getYRot(0.0F), packet.getXRot(0.0F));
        }
        if (this.lastSent != null && this.lastSent.equals(this.unreportedAttack)) {
            this.unreportedAttack = null;
        }
    }

    /**
     * Finishes draining a pending attack look and/or silent camera handback through normal per-tick
     * {@link EventMotion} reports after the owning module disables, without sending out-of-tick
     * or duplicate {@code MovePlayerPacket}s on 1.21.2+ / 26.2 protocols.
     */
    void drainOnBus(final @Nullable MovementCorrection correctionMode) {
        if (!this.isBusy()) {
            this.reset(null);
            return;
        }
        this.drainCorrection = correctionMode;
        this.syncDrainCorrection();
        if (!this.drainingOnBus) {
            this.drainingOnBus = true;
            EventBus.register(this.drainListener);
        }
    }

    private void stopBusDrain() {
        this.drainCorrection = null;
        MovementCorrector corrector = lookupCorrector();
        if (corrector != null) {
            corrector.release(this.drainListener);
        }
        if (this.drainingOnBus) {
            this.drainingOnBus = false;
            EventBus.unregister(this.drainListener);
        }
    }

    private void syncDrainCorrection() {
        MovementCorrector corrector = lookupCorrector();
        if (corrector == null) {
            return;
        }
        Rotation pending = this.unreportedAttack;
        if (pending != null && this.ownsSilentLook && this.drainCorrection != null) {
            corrector.request(this.drainListener, pending, this.drainCorrection);
        } else {
            corrector.release(this.drainListener);
        }
    }

    private static @Nullable MovementCorrector lookupCorrector() {
        return Minecraft.getInstance() != null ? MovementCorrector.current() : null;
    }

    /** Completes a report cancelled last tick before the next tick can select a different look. */
    void flushAttack(final Consumer<Rotation> report) {
        Rotation attackLook = this.unreportedAttack;
        if (attackLook != null) {
            report.accept(attackLook);
            this.lastSent = attackLook;
            this.unreportedAttack = null;
        }
    }

    /** Completes a mid-tick hit before handing the reported look back to the camera. */
    void stop(final @Nullable Rotation camera, final Consumer<Rotation> restore) {
        try {
            this.flushAttack(restore);
            if (camera != null && this.ownsSilentLook && !camera.equals(this.lastSent)) {
                restore.accept(camera);
            }
        } finally {
            this.reset(null);
        }
    }

    private final class DrainListener {
        @EventTarget
        public void onTick(final EventTick event) {
            if (!event.isPre()) {
                return;
            }
            if (!AuraAttackCycle.this.isBusy()) {
                AuraAttackCycle.this.stopBusDrain();
                return;
            }
            AuraAttackCycle.this.syncDrainCorrection();
        }

        @EventTarget
        public void onMotion(final EventMotion event) {
            if (!AuraAttackCycle.this.isBusy()) {
                AuraAttackCycle.this.stopBusDrain();
                return;
            }
            AuraAttackCycle.this.motion(event, null, false);
            if (!AuraAttackCycle.this.isBusy()) {
                AuraAttackCycle.this.stopBusDrain();
            }
        }

        @EventTarget(EventPriority.LOWEST)
        public void onSend(final EventSendPacket event) {
            Minecraft mc = Minecraft.getInstance();
            if ((mc != null && !mc.isSameThread()) || event.isCancelled()) {
                return;
            }
            if (event.getPacket() instanceof ServerboundMovePlayerPacket move) {
                AuraAttackCycle.this.reported(move);
                if (!AuraAttackCycle.this.isBusy()) {
                    AuraAttackCycle.this.stopBusDrain();
                }
            }
        }

        @EventTarget
        public void onLoadWorld(final EventLoadWorld event) {
            AuraAttackCycle.this.reset(null);
        }
    }
}
