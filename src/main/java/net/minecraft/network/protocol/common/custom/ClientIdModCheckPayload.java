package net.minecraft.network.protocol.common.custom;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * ClientID {@code modcheck} payload: the player's profile UUID, sent on play join.
 *
 * <p>Sigma addition for the {@code ClientIdSpoofer} module, following Novinity's ClientID protocol
 * (LiquidBounce PR #8536). Like {@link BrandPayload} it is a UTF-8 string record; unlike upstream it
 * lives in the protocol package so {@code ServerboundCustomPayloadPacket} can register its codec
 * without a mod loader.</p>
 */
public record ClientIdModCheckPayload(String uuid) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientIdModCheckPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("clientid", "modcheck"));
    public static final StreamCodec<FriendlyByteBuf, ClientIdModCheckPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ClientIdModCheckPayload::write, ClientIdModCheckPayload::new);

    private ClientIdModCheckPayload(final FriendlyByteBuf input) {
        this(input.readUtf());
    }

    private void write(final FriendlyByteBuf output) {
        output.writeUtf(this.uuid);
    }

    @Override
    public CustomPacketPayload.Type<ClientIdModCheckPayload> type() {
        return TYPE;
    }
}
