package net.minecraft.network.protocol.common.custom;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * ClientID {@code clientversion} payload: the ClientID version, sent on play join.
 *
 * <p>Sigma addition for the {@code ClientIdSpoofer} module, following Novinity's ClientID protocol
 * (LiquidBounce PR #8536). See {@link ClientIdModCheckPayload} for the shared notes.</p>
 */
public record ClientIdVersionPayload(String version) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientIdVersionPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("clientid", "clientversion"));
    public static final StreamCodec<FriendlyByteBuf, ClientIdVersionPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ClientIdVersionPayload::write, ClientIdVersionPayload::new);

    private ClientIdVersionPayload(final FriendlyByteBuf input) {
        this(input.readUtf());
    }

    private void write(final FriendlyByteBuf output) {
        output.writeUtf(this.version);
    }

    @Override
    public CustomPacketPayload.Type<ClientIdVersionPayload> type() {
        return TYPE;
    }
}
