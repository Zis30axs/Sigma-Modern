package net.minecraft.network.protocol.common;

import com.google.common.collect.Lists;
import com.viaversion.viafabricplus.util.network.DataCustomPayload;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.util.Util;

public record ClientboundCustomPayloadPacket(CustomPacketPayload payload) implements Packet<ClientCommonPacketListener> {
    private static final int MAX_PAYLOAD_SIZE = 1048576;
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundCustomPayloadPacket> GAMEPLAY_STREAM_CODEC = CustomPacketPayload.<RegistryFriendlyByteBuf>codec(
            id -> DiscardedPayload.codec(id, 1048576),
            Util.make(
                Lists.newArrayList(
                    new CustomPacketPayload.TypeAndCodec<>(BrandPayload.TYPE, BrandPayload.STREAM_CODEC),
                    // MODIFIED for porting: makes ViaFabricPlus' sync-task payload decodable. Upstream gets
                    // this from Fabric's PayloadTypeRegistry; Sigma's stand-in registry is a write-only map,
                    // so without this entry packet.payload() could never be a DataCustomPayload and the whole
                    // SyncTasks mechanism (large containers, 1.18.2 block-break acks, game-test markers) was
                    // dead. Registered for the gameplay phase as well as configuration, because all three
                    // producers send it in the play state. The channel id is a per-run random UUID pair, so no
                    // real server can address it.
                    new CustomPacketPayload.TypeAndCodec<>(DataCustomPayload.ID, DataCustomPayload.STREAM_CODEC)
                ),
                types -> {}
            )
        )
        .map(ClientboundCustomPayloadPacket::new, ClientboundCustomPayloadPacket::payload);
    public static final StreamCodec<FriendlyByteBuf, ClientboundCustomPayloadPacket> CONFIG_STREAM_CODEC = CustomPacketPayload.<FriendlyByteBuf>codec(
            id -> DiscardedPayload.codec(id, 1048576),
            List.of(
                new CustomPacketPayload.TypeAndCodec<>(BrandPayload.TYPE, BrandPayload.STREAM_CODEC),
                // MODIFIED for porting: see the gameplay codec above.
                new CustomPacketPayload.TypeAndCodec<>(DataCustomPayload.ID, DataCustomPayload.STREAM_CODEC)
            )
        )
        .map(ClientboundCustomPayloadPacket::new, ClientboundCustomPayloadPacket::payload);

    @Override
    public PacketType<ClientboundCustomPayloadPacket> type() {
        return CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD;
    }

    public void handle(final ClientCommonPacketListener listener) {
        listener.handleCustomPayload(this);
    }
}