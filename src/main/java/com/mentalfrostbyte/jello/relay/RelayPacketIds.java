package com.mentalfrostbyte.jello.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;

/**
 * Resolves native 26.2 network packet IDs directly from {@link GameProtocols}' {@link ProtocolInfo.Details}
 * tables (zero hardcoded protocol IDs, zero reflection) and provides non-mutating VarInt peek/encode helpers.
 */
public final class RelayPacketIds {

    public static final int S2C_BUNDLE_DELIMITER;
    public static final int S2C_LOGIN;
    public static final int S2C_START_CONFIGURATION;

    public static final int C2S_CONFIGURATION_ACKNOWLEDGED;
    public static final int C2S_MOVE_PLAYER_POS;
    public static final int C2S_MOVE_PLAYER_POS_ROT;
    public static final int C2S_MOVE_PLAYER_ROT;
    public static final int C2S_MOVE_PLAYER_STATUS_ONLY;
    public static final int C2S_SET_CARRIED_ITEM;
    public static final int C2S_SWING;
    public static final int C2S_CHAT;
    public static final int C2S_CHAT_COMMAND;
    public static final int C2S_CHAT_COMMAND_SIGNED;

    static {
        Map<PacketType<?>, Integer> clientbound = new HashMap<>();
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets(clientbound::put);

        Map<PacketType<?>, Integer> serverbound = new HashMap<>();
        GameProtocols.SERVERBOUND_TEMPLATE.details().listPackets(serverbound::put);

        S2C_BUNDLE_DELIMITER = requireId(clientbound, GamePacketTypes.CLIENTBOUND_BUNDLE_DELIMITER);
        S2C_LOGIN = requireId(clientbound, GamePacketTypes.CLIENTBOUND_LOGIN);
        S2C_START_CONFIGURATION = requireId(clientbound, GamePacketTypes.CLIENTBOUND_START_CONFIGURATION);

        C2S_CONFIGURATION_ACKNOWLEDGED = requireId(serverbound, GamePacketTypes.SERVERBOUND_CONFIGURATION_ACKNOWLEDGED);
        C2S_MOVE_PLAYER_POS = requireId(serverbound, GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS);
        C2S_MOVE_PLAYER_POS_ROT = requireId(serverbound, GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS_ROT);
        C2S_MOVE_PLAYER_ROT = requireId(serverbound, GamePacketTypes.SERVERBOUND_MOVE_PLAYER_ROT);
        C2S_MOVE_PLAYER_STATUS_ONLY = requireId(serverbound, GamePacketTypes.SERVERBOUND_MOVE_PLAYER_STATUS_ONLY);
        C2S_SET_CARRIED_ITEM = requireId(serverbound, GamePacketTypes.SERVERBOUND_SET_CARRIED_ITEM);
        C2S_SWING = requireId(serverbound, GamePacketTypes.SERVERBOUND_SWING);
        C2S_CHAT = requireId(serverbound, GamePacketTypes.SERVERBOUND_CHAT);
        C2S_CHAT_COMMAND = requireId(serverbound, GamePacketTypes.SERVERBOUND_CHAT_COMMAND);
        C2S_CHAT_COMMAND_SIGNED = requireId(serverbound, GamePacketTypes.SERVERBOUND_CHAT_COMMAND_SIGNED);
    }

    private RelayPacketIds() {
    }

    private static int requireId(final Map<PacketType<?>, Integer> map, final PacketType<?> type) {
        Integer id = map.get(type);
        if (id == null) {
            throw new IllegalStateException("Missing packet network ID for " + type);
        }
        return id;
    }

    /** Reads the leading packet-ID VarInt without mutating {@code buf.readerIndex()}, or returns {@code -1}. */
    public static int peekPacketId(final ByteBuf buf) {
        if (buf == null || !buf.isReadable()) {
            return -1;
        }
        int mark = buf.readerIndex();
        try {
            return VarInt.read(buf);
        } catch (final RuntimeException malformed) {
            return -1;
        } finally {
            buf.readerIndex(mark);
        }
    }

    /** Encodes a typed 26.2 packet into a fresh uncompressed payload buffer {@code [packetId VarInt][body]}. */
    public static <T extends PacketListener> ByteBuf encodePacket(
        final ProtocolInfo<T> protocol,
        final Packet<? super T> packet
    ) {
        ByteBuf buf = Unpooled.buffer();
        try {
            protocol.codec().encode(buf, packet);
            return buf;
        } catch (final Throwable failure) {
            buf.release();
            throw failure;
        }
    }
}
