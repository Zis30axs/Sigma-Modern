package net.minecraft.network.protocol.game;

import com.viaversion.viafabricplus.features.limitation.max_chat_length.MaxChatLength;
import java.time.Instant;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import org.jspecify.annotations.Nullable;

public record ServerboundChatPacket(String message, Instant timeStamp, long salt, @Nullable MessageSignature signature, LastSeenMessages.Update lastSeenMessages)
    implements Packet<ServerGamePacketListener> {
    public static final StreamCodec<FriendlyByteBuf, ServerboundChatPacket> STREAM_CODEC = Packet.codec(
        ServerboundChatPacket::write, ServerboundChatPacket::new
    );

    private ServerboundChatPacket(final FriendlyByteBuf input) {
        this(input.readUtf(256), input.readInstant(), input.readLong(), input.readNullable(MessageSignature::read), new LastSeenMessages.Update(input));
    }

    private void write(final FriendlyByteBuf output) {
        // MODIFIED for porting: was VFP max_chat_length MixinServerboundChatPacket#modifyChatLength (@ModifyConstant write intValue=256)
        // Targets with another limit have to be able to encode it: classic with LONGER_MESSAGES 65534, classic without
        // it 64 - (name + 2), Bedrock 512, <= 1.9.3 100, everything newer the vanilla 256.
        output.writeUtf(this.message, MaxChatLength.getChatLength());
        output.writeInstant(this.timeStamp);
        output.writeLong(this.salt);
        output.writeNullable(this.signature, MessageSignature::write);
        this.lastSeenMessages.write(output);
    }

    @Override
    public PacketType<ServerboundChatPacket> type() {
        return GamePacketTypes.SERVERBOUND_CHAT;
    }

    public void handle(final ServerGamePacketListener listener) {
        listener.handleChat(this);
    }
}