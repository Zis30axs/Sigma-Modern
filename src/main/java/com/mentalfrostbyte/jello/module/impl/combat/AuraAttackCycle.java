package com.mentalfrostbyte.jello.module.impl.combat;

import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import java.util.function.Consumer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.jspecify.annotations.Nullable;

/**
 * Keeps each aura attack followed by a movement report of the look it used, even while standing still.
 * Some servers finish checking a hit against the following report, so having sent the same look before
 * the attack does not finish that attack. Game thread only.
 */
final class AuraAttackCycle {

    private @Nullable Rotation lastSent;
    private @Nullable Rotation unreportedAttack;
    private boolean ownsSilentLook;

    void reset(final @Nullable Rotation initial) {
        this.lastSent = initial;
        this.unreportedAttack = null;
        this.ownsSilentLook = false;
    }

    @Nullable Rotation lastSent() {
        return this.lastSent;
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
            if (desired != null && silent) {
                this.ownsSilentLook = true;
                event.setYaw(desired.yaw());
                event.setPitch(desired.pitch());
                if (!desired.equals(this.lastSent)) {
                    event.forceRotation();
                }
            } else if (this.ownsSilentLook) {
                Rotation camera = new Rotation(event.getYaw(), event.getPitch());
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
            if (this.ownsSilentLook && this.unreportedAttack == null && !(desired != null && silent)
                    && new Rotation(event.getYaw(), event.getPitch()).equals(this.lastSent)) {
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
}
