package com.mentalfrostbyte.jello.selfcheck.host;

import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.util.List;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketDecoder;

/**
 * The four handlers a session puts into a connection's pipeline, and where they go.
 *
 * <pre>
 * in:  … decompress → [INBOUND_TAP] → via-decoder → via-flow-control → [PING_INJECTOR] → decoder → bundler → packet_handler
 * out: packet_handler → hackfix → [PONG_INTERCEPTOR] → unbundler → encoder → via-encoder → [OUTBOUND_TAP] → compress …
 * </pre>
 *
 * <p>The taps sit on the wire side of ViaFabricPlus, so both directions are in the server's protocol - what a
 * server-side anticheat reads - and on the near side of compression and encryption. Everything that does not
 * belong to the self-check passes through untouched.</p>
 *
 * <p>The handlers keep no state of their own (it is all in the session), and are {@code @Sharable} so that
 * {@link #reanchor} can take them out and put them back.</p>
 */
public final class SelfCheckPipeline {

    public static final String INBOUND_TAP = "sigma_selfcheck_in";
    public static final String PING_INJECTOR = "sigma_selfcheck_ping";
    public static final String PONG_INTERCEPTOR = "sigma_selfcheck_pong";
    public static final String OUTBOUND_TAP = "sigma_selfcheck_out";

    /** ViaFabricPlus's flow-control handler behind the Via decoder ({@code ProtocolTranslator.VIA_FLOW_CONTROL}). */
    static final String VIA_FLOW_CONTROL = "via-flow-control";
    /** The vanilla handler right in front of {@code packet_handler}; outbound writes pass it first. */
    static final String HACKFIX = "hackfix";

    private SelfCheckPipeline() {
    }

    /** Puts the handlers in; the pipeline must already carry ViaFabricPlus's handlers. */
    public static void install(final ChannelPipeline pipeline, final SelfCheckSession session) {
        pipeline.addBefore(ViaDecodeHandler.NAME, INBOUND_TAP, new InboundTap(session));
        pipeline.addAfter(pingAnchor(pipeline), PING_INJECTOR, new PingInjector(session));
        pipeline.addBefore(HACKFIX, PONG_INTERCEPTOR, new PongInterceptor());
        pipeline.addBefore(ViaEncodeHandler.NAME, OUTBOUND_TAP, new OutboundTap(session));
    }

    /**
     * Puts the taps back beside the Via handlers. ViaVersion's {@code reorderPipeline} can move the Via decoder
     * and encoder up next to decompression and compression when compression is switched on; the taps would then
     * be on the wrong side of the translation and see the client's own protocol.
     */
    public static void reanchor(final ChannelPipeline pipeline) {
        if (pipeline.get(INBOUND_TAP) == null) {
            return;
        }

        List<String> names = pipeline.names();
        if (names.indexOf(INBOUND_TAP) + 1 != names.indexOf(ViaDecodeHandler.NAME)) {
            ChannelHandler tap = pipeline.remove(INBOUND_TAP);
            pipeline.addBefore(ViaDecodeHandler.NAME, INBOUND_TAP, tap);
        }
        names = pipeline.names();
        if (names.indexOf(OUTBOUND_TAP) + 1 != names.indexOf(ViaEncodeHandler.NAME)) {
            ChannelHandler tap = pipeline.remove(OUTBOUND_TAP);
            pipeline.addBefore(ViaEncodeHandler.NAME, OUTBOUND_TAP, tap);
        }
        names = pipeline.names();
        String anchor = pingAnchor(pipeline);
        if (names.indexOf(anchor) + 1 != names.indexOf(PING_INJECTOR)) {
            ChannelHandler injector = pipeline.remove(PING_INJECTOR);
            pipeline.addAfter(anchor, PING_INJECTOR, injector);
        }
    }

    private static String pingAnchor(final ChannelPipeline pipeline) {
        return pipeline.get(VIA_FLOW_CONTROL) != null ? VIA_FLOW_CONTROL : ViaDecodeHandler.NAME;
    }

    /** Travels down the inbound pipeline and becomes a {@link SelfCheckPing} just behind the decoder. */
    record PingMarker(int id) {
    }

    /** Travels down the outbound pipeline in place of a {@link SelfCheckPong}, to be counted at the outbound tap. */
    record PongMarker(int id) {
    }

    /** Shows the session every packet from the server, and lets it put pings around them. */
    @ChannelHandler.Sharable
    static final class InboundTap extends ChannelInboundHandlerAdapter {

        private final SelfCheckSession session;

        InboundTap(final SelfCheckSession session) {
            this.session = session;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            if (msg instanceof ByteBuf buffer) {
                this.session.clientbound(ctx, buffer);
            } else {
                ctx.fireChannelRead(msg);
            }
        }

        @Override
        public void channelReadComplete(final ChannelHandlerContext ctx) {
            this.session.inboundBatchEnd(ctx);
            ctx.fireChannelReadComplete();
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            this.session.close("disconnected");
            super.channelInactive(ctx);
        }
    }

    /**
     * Turns a {@link PingMarker} into a {@link SelfCheckPing} right behind the decoder, so it is bundled, queued
     * and handled in step with the packets around it. A ping only exists in the configuration and play phases
     * (and for servers older than 1.20.2 only in play, see {@link SelfCheckSession#pingsAllowedInConfiguration});
     * in any other phase, or while the decoder is being swapped, the ping is given up on instead.
     */
    @ChannelHandler.Sharable
    static final class PingInjector extends ChannelInboundHandlerAdapter {

        private final SelfCheckSession session;

        PingInjector(final SelfCheckSession session) {
            this.session = session;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            if (!(msg instanceof PingMarker marker)) {
                ctx.fireChannelRead(msg);
                return;
            }

            ChannelHandlerContext decoder = ctx.pipeline().context(HandlerNames.DECODER);
            if (decoder != null && decoder.handler() instanceof PacketDecoder<?> packetDecoder && this.accepts(packetDecoder.protocol())) {
                decoder.fireChannelRead(new SelfCheckPing(marker.id()));
            } else {
                this.session.transactionDropped(marker.id());
            }
        }

        private boolean accepts(final ConnectionProtocol phase) {
            return phase == ConnectionProtocol.PLAY
                    || phase == ConnectionProtocol.CONFIGURATION && this.session.pingsAllowedInConfiguration();
        }
    }

    /**
     * Takes the answers to the self-check's own pings out before the encoder. This is not tied to a session: an
     * answer that turns up after the session ended must not reach the server either.
     */
    @ChannelHandler.Sharable
    static final class PongInterceptor extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
            if (msg instanceof SelfCheckPong pong) {
                ctx.write(new PongMarker(pong.getId()), promise);
            } else {
                ctx.write(msg, promise);
            }
        }
    }

    /** Shows the session every packet that leaves, and where the answers to its pings fall among them. */
    @ChannelHandler.Sharable
    static final class OutboundTap extends ChannelOutboundHandlerAdapter {

        private final SelfCheckSession session;

        OutboundTap(final SelfCheckSession session) {
            this.session = session;
        }

        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
            if (msg instanceof PongMarker marker) {
                this.session.transactionAnswered(marker.id());
                promise.trySuccess();
                return;
            }
            if (msg instanceof ByteBuf buffer) {
                this.session.serverbound(buffer);
            }
            ctx.write(msg, promise);
        }
    }
}
