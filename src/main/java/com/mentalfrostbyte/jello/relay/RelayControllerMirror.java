package com.mentalfrostbyte.jello.relay;

import io.netty.buffer.ByteBuf;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Non-mutating mirror of Controller B's live serverbound PLAY packets so Host A can smoothly follow
 * B's position, camera angles, selected hotbar slot, and arm swings while acting as a live spectator.
 */
public final class RelayControllerMirror {

    private volatile boolean hasPosition;
    private volatile boolean hasRotation;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yRot;
    private volatile float xRot;
    private volatile boolean onGround;
    private volatile boolean horizontalCollision;
    private volatile int selectedHotbarSlot = -1;
    private final AtomicLong swingSequence = new AtomicLong();
    private volatile InteractionHand lastSwingHand = InteractionHand.MAIN_HAND;
    private long appliedSwingSequence;

    public void reset() {
        this.hasPosition = false;
        this.hasRotation = false;
        this.selectedHotbarSlot = -1;
        this.appliedSwingSequence = this.swingSequence.get();
    }

    public void seedFromHost(final LocalPlayer player) {
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        this.yRot = player.getYRot();
        this.xRot = player.getXRot();
        this.onGround = player.onGround();
        this.horizontalCollision = player.horizontalCollision;
        this.selectedHotbarSlot = player.getInventory().getSelectedSlot();
        this.hasPosition = true;
        this.hasRotation = true;
        this.appliedSwingSequence = this.swingSequence.get();
    }

    /**
     * Peeks a raw uncompressed 26.2 serverbound PLAY packet from Controller B without mutating
     * {@code rawPacket.readerIndex()}.
     */
    public void observeControllerPacket(final int packetId, final ByteBuf rawPacket) {
        if (rawPacket == null || !rawPacket.isReadable()) {
            return;
        }
        ByteBuf slice = rawPacket.slice();
        try {
            VarInt.read(slice); // skip packet ID on slice only
            FriendlyByteBuf friendly = new FriendlyByteBuf(slice);

            if (packetId == RelayPacketIds.C2S_MOVE_PLAYER_POS) {
                applyMove(ServerboundMovePlayerPacket.Pos.STREAM_CODEC.decode(friendly));
            } else if (packetId == RelayPacketIds.C2S_MOVE_PLAYER_POS_ROT) {
                applyMove(ServerboundMovePlayerPacket.PosRot.STREAM_CODEC.decode(friendly));
            } else if (packetId == RelayPacketIds.C2S_MOVE_PLAYER_ROT) {
                applyMove(ServerboundMovePlayerPacket.Rot.STREAM_CODEC.decode(friendly));
            } else if (packetId == RelayPacketIds.C2S_MOVE_PLAYER_STATUS_ONLY) {
                applyMove(ServerboundMovePlayerPacket.StatusOnly.STREAM_CODEC.decode(friendly));
            } else if (packetId == RelayPacketIds.C2S_SET_CARRIED_ITEM) {
                ServerboundSetCarriedItemPacket slotPacket = ServerboundSetCarriedItemPacket.STREAM_CODEC.decode(friendly);
                int slot = slotPacket.getSlot();
                if (slot >= 0 && slot < 9) {
                    this.selectedHotbarSlot = slot;
                }
            } else if (packetId == RelayPacketIds.C2S_SWING) {
                ServerboundSwingPacket swingPacket = ServerboundSwingPacket.STREAM_CODEC.decode(friendly);
                this.lastSwingHand = swingPacket.getHand();
                this.swingSequence.incrementAndGet();
            }
        } catch (final RuntimeException ignored) {
            // Never disrupt packet forwarding on a malformed or custom packet.
        }
    }

    private void applyMove(final ServerboundMovePlayerPacket packet) {
        if (packet.hasPosition()) {
            double nx = packet.getX(this.x);
            double ny = packet.getY(this.y);
            double nz = packet.getZ(this.z);
            if (Double.isFinite(nx) && Double.isFinite(ny) && Double.isFinite(nz)) {
                this.x = nx;
                this.y = ny;
                this.z = nz;
                this.hasPosition = true;
            }
        }
        if (packet.hasRotation()) {
            float nyRot = packet.getYRot(this.yRot);
            float nxRot = packet.getXRot(this.xRot);
            if (Float.isFinite(nyRot) && Float.isFinite(nxRot)) {
                this.yRot = nyRot;
                this.xRot = Math.clamp(nxRot, -90.0F, 90.0F);
                this.hasRotation = true;
            }
        }
        this.onGround = packet.isOnGround();
        this.horizontalCollision = packet.horizontalCollision();
    }

    /** Peeks chat or command text from a 26.2 serverbound chat/command packet without moving {@code readerIndex}. */
    public static @Nullable String peekChatOrCommandText(final int packetId, final ByteBuf rawPacket) {
        if (packetId != RelayPacketIds.C2S_CHAT
            && packetId != RelayPacketIds.C2S_CHAT_COMMAND
            && packetId != RelayPacketIds.C2S_CHAT_COMMAND_SIGNED) {
            return null;
        }
        ByteBuf slice = rawPacket.slice();
        try {
            VarInt.read(slice);
            FriendlyByteBuf friendly = new FriendlyByteBuf(slice);
            return friendly.readUtf(256);
        } catch (final RuntimeException malformed) {
            return null;
        }
    }

    /** Applies Controller B's mirrored pose/hotbar/swings onto Host A's {@link LocalPlayer} on the main thread. */
    public void applyToHostPlayer(final LocalPlayer player) {
        if (this.hasPosition && !player.isPassenger()) {
            player.setPos(this.x, this.y, this.z);
            player.setDeltaMovement(Vec3.ZERO);
        }
        if (this.hasRotation) {
            player.setYRot(this.yRot);
            player.setXRot(this.xRot);
            player.yHeadRot = this.yRot;
            player.yBodyRot = this.yRot;
        }
        player.setOnGround(this.onGround);
        player.horizontalCollision = this.horizontalCollision;

        int slot = this.selectedHotbarSlot;
        if (slot >= 0 && slot < 9 && player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }

        long latestSwing = this.swingSequence.get();
        if (latestSwing != this.appliedSwingSequence) {
            this.appliedSwingSequence = latestSwing;
            player.swing(this.lastSwingHand, false);
        }
    }

    public boolean hasPosition() {
        return this.hasPosition;
    }

    public boolean hasRotation() {
        return this.hasRotation;
    }

    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double z() {
        return this.z;
    }

    public float yRot() {
        return this.yRot;
    }

    public float xRot() {
        return this.xRot;
    }

    public boolean onGround() {
        return this.onGround;
    }

    public int selectedHotbarSlot() {
        return this.selectedHotbarSlot;
    }

    public long swingCount() {
        return this.swingSequence.get();
    }

    public InteractionHand lastSwingHand() {
        return this.lastSwingHand;
    }
}
