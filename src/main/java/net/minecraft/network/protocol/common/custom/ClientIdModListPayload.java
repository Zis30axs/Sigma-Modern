package net.minecraft.network.protocol.common.custom;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * ClientID {@code modlist} payload: comma-separated mod IDs, sent on play join.
 *
 * <p>Sigma addition for the {@code ClientIdSpoofer} module, following Novinity's ClientID protocol
 * (LiquidBounce PR #8536). See {@link ClientIdModCheckPayload} for the shared notes.</p>
 */
public record ClientIdModListPayload(String list) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientIdModListPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("clientid", "modlist"));
    public static final StreamCodec<FriendlyByteBuf, ClientIdModListPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ClientIdModListPayload::write, ClientIdModListPayload::new);

    private ClientIdModListPayload(final FriendlyByteBuf input) {
        this(input.readUtf());
    }

    private void write(final FriendlyByteBuf output) {
        output.writeUtf(this.list);
    }

    @Override
    public CustomPacketPayload.Type<ClientIdModListPayload> type() {
        return TYPE;
    }
}
