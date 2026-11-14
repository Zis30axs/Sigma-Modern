package net.minecraft.network.protocol.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.ClientIdModCheckPayload;
import net.minecraft.network.protocol.common.custom.ClientIdModListPayload;
import net.minecraft.network.protocol.common.custom.ClientIdPackListPayload;
import net.minecraft.network.protocol.common.custom.ClientIdVersionPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;

/**
 * Proves the ClientID payloads survive the serverbound custom-payload codec.
 *
 * <p>The codec only knows the payload types listed in
 * {@code ServerboundCustomPayloadPacket.STREAM_CODEC}; an unlisted type falls back to
 * {@code DiscardedPayload} and loses its content on encode, so a round-trip that comes back as
 * the original record is the whole point of this test.</p>
 */
class ClientIdPayloadCodecTest {

    @Test
    void clientIdPayloadsRoundTripThroughPacketCodec() {
        assertRoundTrip(new ClientIdModCheckPayload("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertRoundTrip(new ClientIdModListPayload("minecraft,java,fabricloader,fabric-api,clientid"));
        assertRoundTrip(new ClientIdPackListPayload("Default"));
        assertRoundTrip(new ClientIdVersionPayload("1.1.3"));
    }

    private static void assertRoundTrip(final CustomPacketPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ServerboundCustomPayloadPacket.STREAM_CODEC.encode(buf, new ServerboundCustomPayloadPacket(payload));
        ServerboundCustomPayloadPacket decoded = ServerboundCustomPayloadPacket.STREAM_CODEC.decode(buf);
        assertEquals(payload, decoded.payload());
    }
}
