package net.fabricmc.fabric.api.networking.v1;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// MODIFIED for porting: embedded stand-in for fabric-api (codecs stored; channel wiring happens in the port bootstrap)
public final class PayloadTypeRegistry {
    private static final Registry CLIENTBOUND_CONFIGURATION = new Registry();
    private static final Registry PLAY_C2S = new Registry();
    private static final Registry PLAY_S2C = new Registry();

    private PayloadTypeRegistry() {
    }

    @SuppressWarnings("unchecked")
    public static Registry clientboundConfiguration() {
        return CLIENTBOUND_CONFIGURATION;
    }

    @SuppressWarnings("unchecked")
    public static Registry playC2S() {
        return PLAY_C2S;
    }

    @SuppressWarnings("unchecked")
    public static Registry playS2C() {
        return PLAY_S2C;
    }

    public static final class Registry {
        private final Map<CustomPacketPayload.Type<?>, StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload>> codecs = new ConcurrentHashMap<>();

        public void register(final CustomPacketPayload.Type<? extends CustomPacketPayload> type, final StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> codec) {
            codecs.put(type, codec);
        }
    }
}
