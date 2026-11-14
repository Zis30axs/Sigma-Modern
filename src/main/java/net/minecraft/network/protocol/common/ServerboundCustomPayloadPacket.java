package net.minecraft.network.protocol.common;

import com.google.common.collect.Lists;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.ClientIdModCheckPayload;
import net.minecraft.network.protocol.common.custom.ClientIdModListPayload;
import net.minecraft.network.protocol.common.custom.ClientIdPackListPayload;
import net.minecraft.network.protocol.common.custom.ClientIdVersionPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.util.Util;

public record ServerboundCustomPayloadPacket(CustomPacketPayload payload) implements Packet<ServerCommonPacketListener> {
    private static final int MAX_PAYLOAD_SIZE = 32767;
    public static final StreamCodec<FriendlyByteBuf, ServerboundCustomPayloadPacket> STREAM_CODEC = CustomPacketPayload.<FriendlyByteBuf>codec(
            id -> DiscardedPayload.codec(id, 32767),
            Util.make(Lists.newArrayList(new CustomPacketPayload.TypeAndCodec<>(BrandPayload.TYPE, BrandPayload.STREAM_CODEC)), types -> {
                // Sigma hook: ClientID spoofer payloads, sent by the ClientIdSpoofer module.
                // Unknown payload types would otherwise fall back to DiscardedPayload and lose
                // their content on encode, so they are registered here like BrandPayload.
                types.add(new CustomPacketPayload.TypeAndCodec<>(ClientIdModCheckPayload.TYPE, ClientIdModCheckPayload.STREAM_CODEC));
                types.add(new CustomPacketPayload.TypeAndCodec<>(ClientIdModListPayload.TYPE, ClientIdModListPayload.STREAM_CODEC));
                types.add(new CustomPacketPayload.TypeAndCodec<>(ClientIdPackListPayload.TYPE, ClientIdPackListPayload.STREAM_CODEC));
                types.add(new CustomPacketPayload.TypeAndCodec<>(ClientIdVersionPayload.TYPE, ClientIdVersionPayload.STREAM_CODEC));
            })
        )
        .map(ServerboundCustomPayloadPacket::new, ServerboundCustomPayloadPacket::payload);

    @Override
    public PacketType<ServerboundCustomPayloadPacket> type() {
        return CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD;
    }

    public void handle(final ServerCommonPacketListener listener) {
        listener.handleCustomPayload(this);
    }
}