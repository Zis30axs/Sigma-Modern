package com.mentalfrostbyte.jello.module.impl.world;

import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import java.util.Objects;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

/** Packet-local state for the four Grim options from the original Heypixel mode. */
final class GrimDisablerState {
    record Options(boolean badPacketA, boolean badPacketF, boolean aimModulo360, boolean duplicateRotPlace) {}

    private Options options;
    private int lastSlot = -1;
    private Boolean lastSprinting;
    private int moduloCount;
    private boolean hasLook;
    private float logicalYaw;
    private float logicalPitch;
    private float wireYaw;
    private float wirePitch;
    private float deltaYaw;
    private float lastPlacedDeltaYaw;
    private boolean rotated;
    private boolean hasPlacedDelta;

    void reset() {
        this.options = null;
        this.lastSlot = -1;
        this.lastSprinting = null;
        this.moduloCount = 0;
        this.hasLook = false;
        this.logicalYaw = this.logicalPitch = this.wireYaw = this.wirePitch = 0;
        this.deltaYaw = this.lastPlacedDeltaYaw = 0;
        this.rotated = this.hasPlacedDelta = false;
    }

    void apply(EventSendPacket event, int localEntityId, Options options) {
        if (event.isCancelled()) return;
        Objects.requireNonNull(options, "options");
        if (!options.equals(this.options)) {
            this.reset();
            this.options = options;
        }

        Packet<?> packet = event.getPacket();
        if (packet instanceof ServerboundSetCarriedItemPacket held) {
            int slot = held.getSlot();
            if (slot < 0 || slot > 8) {
                this.lastSlot = -1;
            } else {
                if (options.badPacketA() && slot == this.lastSlot) event.cancel();
                this.lastSlot = slot;
            }
        } else if (packet instanceof ServerboundPlayerCommandPacket command && command.getId() == localEntityId) {
            Boolean sprinting = switch (command.getAction()) {
                case START_SPRINTING -> true;
                case STOP_SPRINTING -> false;
                default -> null;
            };
            if (sprinting != null) {
                if (options.badPacketF() && sprinting.equals(this.lastSprinting)) event.cancel();
                this.lastSprinting = sprinting;
            }
        } else if (packet instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            this.move(event, move, options);
        } else if (packet instanceof ServerboundUseItemOnPacket && options.duplicateRotPlace() && this.rotated) {
            this.lastPlacedDeltaYaw = this.deltaYaw;
            this.hasPlacedDelta = true;
            this.rotated = false;
        } else if (packet instanceof ServerboundUseItemPacket use && this.hasLook
                && (options.aimModulo360() || options.duplicateRotPlace()) && this.wireYaw != this.logicalYaw
                && sameLook(use.getYRot(), use.getXRot(), this.logicalYaw, this.logicalPitch)) {
            // Modern use packets carry their own aim. Keep the same direction as the movement already sent.
            if (use.getYRot() != this.wireYaw || use.getXRot() != this.wirePitch) {
                event.setPacket(new ServerboundUseItemPacket(use.getHand(), use.getSequence(), this.wireYaw, this.wirePitch));
            }
        }
    }

    private void move(EventSendPacket event, ServerboundMovePlayerPacket move, Options options) {
        float yaw = move.getYRot(0), pitch = move.getXRot(0);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            this.hasLook = this.rotated = this.hasPlacedDelta = false;
            return;
        }

        float adjustedYaw = yaw;
        if (options.duplicateRotPlace() && this.hasLook) {
            // Preserve the original mode's raw logical delta, before jitter or the full-turn offset.
            this.deltaYaw = Math.abs(yaw - this.logicalYaw);
            this.rotated = Float.isFinite(this.deltaYaw);
            if (this.rotated && this.hasPlacedDelta && this.deltaYaw > 2
                    && Math.abs(this.deltaYaw - this.lastPlacedDeltaYaw) < 0.0001F) {
                adjustedYaw += 0.0002F;
            }
        }
        if (options.aimModulo360()) {
            adjustedYaw += this.moduloCount * 360;
            this.moduloCount = (this.moduloCount + 5) % 100;
        }

        // Large float magnitudes can turn an integral number of turns into a different direction.
        if (!Float.isFinite(adjustedYaw) || angularDifference(adjustedYaw, yaw) > 0.01) adjustedYaw = yaw;
        this.logicalYaw = yaw;
        this.logicalPitch = pitch;
        this.wireYaw = adjustedYaw;
        this.wirePitch = pitch;
        this.hasLook = true;
        if (adjustedYaw == yaw) return;
        if (move.hasPosition()) {
            event.setPacket(new ServerboundMovePlayerPacket.PosRot(move.getX(0), move.getY(0), move.getZ(0),
                    adjustedYaw, pitch, move.isOnGround(), move.horizontalCollision()));
        } else {
            event.setPacket(new ServerboundMovePlayerPacket.Rot(adjustedYaw, pitch, move.isOnGround(), move.horizontalCollision()));
        }
    }

    boolean matchesTransformedLook(float yaw, float pitch, float expectedYaw, float expectedPitch) {
        return this.hasLook && yaw == this.wireYaw && pitch == this.wirePitch
                && sameLook(this.logicalYaw, this.logicalPitch, expectedYaw, expectedPitch)
                && angularDifference(this.wireYaw, this.logicalYaw) <= 0.01;
    }

    private static boolean sameLook(float yaw, float pitch, float expectedYaw, float expectedPitch) {
        return Float.isFinite(yaw) && Float.isFinite(pitch) && Float.isFinite(expectedYaw) && Float.isFinite(expectedPitch)
                && angularDifference(yaw, expectedYaw) < 0.001 && Math.abs((double) pitch - expectedPitch) < 0.001;
    }

    private static double angularDifference(float yaw, float other) {
        double difference = ((double) yaw - other) % 360.0;
        if (difference >= 180) difference -= 360;
        if (difference < -180) difference += 360;
        return Math.abs(difference);
    }
}
