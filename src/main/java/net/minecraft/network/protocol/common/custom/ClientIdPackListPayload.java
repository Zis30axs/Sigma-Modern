package net.minecraft.network.protocol.common.custom;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * ClientID {@code packlist} payload: comma-separated enabled resource-pack names, sent on play join.
 *
 * <p>Sigma addition for the {@code ClientIdSpoofer} module, following Novinity's ClientID protocol
 * (LiquidBounce PR #8536). See {@link ClientIdModCheckPayload} for the shared notes.</p>
 */
public record ClientIdPackListPayload(String list) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientIdPackListPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("clientid", "packlist"));
    public static final StreamCodec<FriendlyByteBuf, ClientIdPackListPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ClientIdPackListPayload::write, ClientIdPackListPayload::new);

    private ClientIdPackListPayload(final FriendlyByteBuf input) {
        this(input.readUtf());
    }

    private void write(final FriendlyByteBuf output) {
        output.writeUtf(this.list);
    }

    @Override
    public CustomPacketPayload.Type<ClientIdPackListPayload> type() {
        return TYPE;
    }
}
