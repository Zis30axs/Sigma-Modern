package com.mentalfrostbyte.jello.selfcheck;

import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.flow.FlowControlHandler;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * A stand-in for a client connection's pipeline: the real vanilla {@link PacketDecoder} and the same handler names
 * ViaFabricPlus and vanilla use, with one-byte packets instead of the real protocol.
 *
 * <pre>
 * in:  via-decoder(pass) → via-flow-control(FlowControlHandler) → decoder(PacketDecoder, PLAY) → packet_handler(collects)
 * out: packet_handler → hackfix → encoder(one byte per packet) → via-encoder(pass) → head (outboundMessages)
 * </pre>
 */
public final class TestWire {

    /** A packet from the test protocol; its wire form is the single byte {@code number}. */
    public record TestPacket(int number) implements Packet<ClientGamePacketListener> {

        static final PacketType<TestPacket> TYPE = new PacketType<>(PacketFlow.CLIENTBOUND, Identifier.withDefaultNamespace("selfcheck_test"));

        @Override
        public PacketType<TestPacket> type() {
            return TYPE;
        }

        @Override
        public void handle(final ClientGamePacketListener listener) {
        }
    }

    public static ProtocolInfo<ClientGamePacketListener> protocol(final ConnectionProtocol id) {
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = StreamCodec.of(
                (buffer, packet) -> buffer.writeByte(((TestPacket) packet).number()),
                buffer -> new TestPacket(buffer.readUnsignedByte()));
        return new ProtocolInfo<>() {
            @Override
            public ConnectionProtocol id() {
                return id;
            }

            @Override
            public PacketFlow flow() {
                return PacketFlow.CLIENTBOUND;
            }

            @Override
            public StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec() {
                return codec;
            }

            @Override
            public @Nullable BundlerInfo bundlerInfo() {
                return null;
            }
        };
    }

    /** What reached {@code packet_handler}, in order. */
    public static final class Collector extends ChannelInboundHandlerAdapter {

        public final List<Object> received = new ArrayList<>();

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            this.received.add(msg);
        }
    }

    /**
     * Stands in for ViaVersion's handlers: passes everything through untouched. Sharable like the real ones, which
     * {@code ViaChannelInitializer.reorderPipeline} removes and adds back.
     */
    @ChannelHandler.Sharable
    static final class PassInbound extends ChannelInboundHandlerAdapter {
    }

    @ChannelHandler.Sharable
    static final class PassOutbound extends ChannelOutboundHandlerAdapter {
    }

    /** Encodes test packets and pongs to one-byte / {@code 0xFE, id} wire forms, like the vanilla encoder would. */
    static final class Encoder extends MessageToByteEncoder<Packet<?>> {

        @Override
        protected void encode(final ChannelHandlerContext ctx, final Packet<?> packet, final ByteBuf out) {
            if (packet instanceof ServerboundPongPacket pong) {
                out.writeByte(0xFE).writeShort(pong.getId());
            } else {
                out.writeByte(((TestPacket) packet).number());
            }
        }
    }

    public final EmbeddedChannel channel = new EmbeddedChannel();
    public final Collector packetHandler = new Collector();

    public TestWire(final ConnectionProtocol phase) {
        this.channel.pipeline()
                .addLast(ViaDecodeHandler.NAME, new PassInbound())
                .addLast("via-flow-control", new FlowControlHandler())
                .addLast(HandlerNames.DECODER, new PacketDecoder<>(protocol(phase)))
                .addLast(ViaEncodeHandler.NAME, new PassOutbound())
                .addLast(HandlerNames.ENCODER, new Encoder())
                .addLast("hackfix", new PassOutbound())
                .addLast(HandlerNames.PACKET_HANDLER, this.packetHandler);
    }

    /** Delivers wire packets {@code numbers}, one read each, then ends the read batch. */
    public void receive(final int... numbers) {
        for (int number : numbers) {
            this.channel.pipeline().fireChannelRead(Unpooled.wrappedBuffer(new byte[]{(byte) number}));
        }
        this.channel.pipeline().fireChannelReadComplete();
        this.channel.runPendingTasks();
    }

    /** The first byte of every buffer that left through the head, in order; pongs show up as {@code 0xFE}. */
    public List<Integer> sent() {
        List<Integer> bytes = new ArrayList<>();
        Object message;
        while ((message = this.channel.readOutbound()) != null) {
            ByteBuf buffer = (ByteBuf) message;
            bytes.add((int) buffer.getUnsignedByte(buffer.readerIndex()));
            buffer.release();
        }
        return bytes;
    }
}
