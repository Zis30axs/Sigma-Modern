package com.mentalfrostbyte.jello.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.NoSuchElementException;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs and maintains Sigma Relay's three Netty handlers on Host A's {@code Connection} channel,
 * anchored on the native 26.2 side of ViaFabricPlus ({@code decoder} / {@code encoder}):
 *
 * <ul>
 *   <li>{@code sigma_relay_inbound} (before {@code decoder}): copies native 26.2 {@code S -> A} PLAY packets
 *       to Controller B, tracks packet bundles so B never attaches mid-bundle, and detects sub-server
 *       {@code StartConfiguration} transitions.</li>
 *   <li>{@code sigma_relay_drop} (after {@code encoder}): suppresses Host A's gameplay {@link Packet}s while
 *       Controller B is in PLAY, while letting B's injected {@link ByteBuf}s, pipeline configuration tasks,
 *       protocol handshakes, custom payloads, and delegated signed chat packets pass through.</li>
 *   <li>{@code sigma_relay_inject} (after {@code sigma_relay_drop}): injection point for {@code B -> P -> S}
 *       native 26.2 serverbound {@link ByteBuf}s.</li>
 * </ul>
 */
public final class RelayChannelBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("SigmaRelay");

    public static final String HANDLER_INBOUND = "sigma_relay_inbound";
    public static final String HANDLER_DROP = "sigma_relay_drop";
    public static final String HANDLER_INJECT = "sigma_relay_inject";
    public static final String HANDLER_WATCH = "sigma_relay_watch";

    private RelayChannelBridge() {
    }

    public static void install(final Channel channel, final RelayCoordinator coordinator) {
        coordinator.setHostChannel(channel);
        if (channel.eventLoop().inEventLoop()) {
            doInstall(channel, coordinator);
        } else {
            channel.eventLoop().execute(() -> doInstall(channel, coordinator));
        }
    }

    public static void uninstall(final Channel channel, final RelayCoordinator coordinator) {
        Runnable task = () -> {
            ChannelPipeline pipeline = channel.pipeline();
            removeQuietly(pipeline, HANDLER_WATCH);
            removeQuietly(pipeline, HANDLER_INJECT);
            removeQuietly(pipeline, HANDLER_DROP);
            removeQuietly(pipeline, HANDLER_INBOUND);
            coordinator.clearHostChannelIfMatches(channel);
        };
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else if (channel.isOpen()) {
            channel.eventLoop().execute(task);
        } else {
            coordinator.clearHostChannelIfMatches(channel);
        }
    }

    /** Re-anchors Relay handlers after compression or protocol swaps if already installed on {@code pipeline}. */
    public static void reanchor(final ChannelPipeline pipeline) {
        if (pipeline == null || pipeline.get(HANDLER_WATCH) == null) {
            return;
        }
        Channel ch = pipeline.channel();
        if (ch != null) {
            doInstall(ch, RelayCoordinator.get());
        }
    }

    static void doInstall(final Channel channel, final RelayCoordinator coordinator) {
        if (!channel.isOpen() && !channel.isRegistered()) {
            return;
        }
        ChannelPipeline p = channel.pipeline();
        try {
            InboundCopy existingInbound = p.get(InboundCopy.class);
            boolean preserveBundleState = existingInbound != null && existingInbound.inServerBundle;

            removeQuietly(p, HANDLER_INJECT);
            removeQuietly(p, HANDLER_DROP);
            removeQuietly(p, HANDLER_INBOUND);

            String inboundAnchor = findAnchor(p, "decoder", "inbound_config");
            String outboundAnchor = findAnchor(p, "encoder", "outbound_config");
            if (inboundAnchor == null || outboundAnchor == null) {
                return;
            }

            InboundCopy inbound = new InboundCopy(coordinator);
            inbound.inServerBundle = preserveBundleState;
            p.addBefore(inboundAnchor, HANDLER_INBOUND, inbound);
            p.addAfter(outboundAnchor, HANDLER_DROP, new OutboundDrop(coordinator));
            p.addAfter(HANDLER_DROP, HANDLER_INJECT, new OutboundInject(coordinator));

            if (p.get(HANDLER_WATCH) == null) {
                String watchAnchor = p.get("packet_handler") != null ? "packet_handler" : null;
                ProtocolWatch watch = new ProtocolWatch(channel, coordinator);
                if (watchAnchor != null) {
                    p.addBefore(watchAnchor, HANDLER_WATCH, watch);
                } else {
                    p.addLast(HANDLER_WATCH, watch);
                }
            }
        } catch (final Throwable t) {
            LOGGER.warn("Failed to anchor Sigma Relay handlers on {}", channel, t);
        }
    }

    private static String findAnchor(final ChannelPipeline p, final String primary, final String fallback) {
        if (p.get(primary) != null) {
            return primary;
        }
        if (p.get(fallback) != null) {
            return fallback;
        }
        return null;
    }

    private static void removeQuietly(final ChannelPipeline p, final String name) {
        try {
            if (p.get(name) != null) {
                p.remove(name);
            }
        } catch (final NoSuchElementException ignored) {
        }
    }

    /** Checks whether {@code pipeline}'s inbound decoder is currently decoding native 26.2 {@code PLAY}. */
    static boolean isPlayInbound(final ChannelPipeline pipeline) {
        ChannelHandler decoder = pipeline.get("decoder");
        return decoder instanceof PacketDecoder<?> packetDecoder
            && packetDecoder.protocol() == ConnectionProtocol.PLAY;
    }

    /** Watches for protocol phase swaps that replace {@code decoder}/{@code encoder} and re-anchors cleanly. */
    static final class ProtocolWatch extends ChannelInboundHandlerAdapter {
        private final Channel channel;
        private final RelayCoordinator coordinator;
        private volatile boolean scheduled;

        ProtocolWatch(final Channel channel, final RelayCoordinator coordinator) {
            this.channel = channel;
            this.coordinator = coordinator;
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            checkAnchors(ctx);
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            checkAnchors(ctx);
            super.channelRead(ctx, msg);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            this.coordinator.clearHostChannelIfMatches(this.channel);
            super.channelInactive(ctx);
        }

        private void checkAnchors(final ChannelHandlerContext ctx) {
            if (this.scheduled) {
                return;
            }
            ChannelPipeline p = ctx.pipeline();
            if (isProperlyOrdered(p)) {
                return;
            }
            this.scheduled = true;
            ctx.channel().eventLoop().execute(() -> {
                try {
                    if (p.get(HANDLER_WATCH) != null) {
                        doInstall(this.channel, this.coordinator);
                    }
                } finally {
                    this.scheduled = false;
                }
            });
        }

        private static boolean isProperlyOrdered(final ChannelPipeline p) {
            java.util.List<String> names = p.names();
            int inboundIdx = names.indexOf(HANDLER_INBOUND);
            int dropIdx = names.indexOf(HANDLER_DROP);
            int injectIdx = names.indexOf(HANDLER_INJECT);
            if (inboundIdx < 0 || dropIdx < 0 || injectIdx < 0) {
                return false;
            }
            int decoderIdx = names.indexOf("decoder");
            if (decoderIdx < 0) {
                decoderIdx = names.indexOf("inbound_config");
            }
            int encoderIdx = names.indexOf("encoder");
            if (encoderIdx < 0) {
                encoderIdx = names.indexOf("outbound_config");
            }
            return decoderIdx > inboundIdx && encoderIdx >= 0 && dropIdx > encoderIdx && injectIdx > dropIdx;
        }
    }

    /** Copies native 26.2 {@code S -> A} PLAY packets to Controller B while guarding bundle boundaries. */
    public static final class InboundCopy extends ChannelInboundHandlerAdapter {
        private final RelayCoordinator coordinator;
        boolean inServerBundle;
        private boolean relayStarted;

        public InboundCopy(final RelayCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (msg instanceof ByteBuf buf && isPlayInbound(ctx.pipeline())) {
                int packetId = RelayPacketIds.peekPacketId(buf);

                if (packetId == RelayPacketIds.S2C_START_CONFIGURATION) {
                    this.inServerBundle = false;
                    this.relayStarted = false;
                    this.coordinator.onServerStartConfiguration();
                    ctx.fireChannelRead(msg);
                    return;
                }

                if (this.coordinator.isServerInConfiguration()) {
                    if (packetId == RelayPacketIds.S2C_LOGIN) {
                        this.inServerBundle = false;
                        this.relayStarted = false;
                        this.coordinator.onServerReenteredPlay();
                    }
                    ctx.fireChannelRead(msg);
                    return;
                }

                boolean isDelimiter = packetId == RelayPacketIds.S2C_BUNDLE_DELIMITER;
                if (this.coordinator.isControllerConnected()) {
                    if (!this.relayStarted) {
                        if (!this.inServerBundle) {
                            // Safe packet boundary: start forwarding (including a fresh opening delimiter).
                            this.relayStarted = true;
                            if (isDelimiter) {
                                this.inServerBundle = true;
                            }
                            this.coordinator.forwardServerPacketToController(buf.retainedDuplicate());
                        } else if (isDelimiter) {
                            // Closing delimiter of a bundle that started before B attached: skip it and start next packet.
                            this.inServerBundle = false;
                            this.relayStarted = true;
                        }
                    } else {
                        if (isDelimiter) {
                            this.inServerBundle = !this.inServerBundle;
                        }
                        this.coordinator.forwardServerPacketToController(buf.retainedDuplicate());
                    }
                } else {
                    this.relayStarted = false;
                    if (isDelimiter) {
                        this.inServerBundle = !this.inServerBundle;
                    }
                }
            } else {
                this.inServerBundle = false;
                this.relayStarted = false;
            }
            ctx.fireChannelRead(msg);
        }
    }

    /** Suppresses Host A's gameplay {@link Packet}s while Controller B is actively controlling in PLAY. */
    public static final class OutboundDrop extends ChannelOutboundHandlerAdapter {
        private final RelayCoordinator coordinator;

        public OutboundDrop(final RelayCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
            if (!this.coordinator.isControllerActivelyControlling()) {
                ctx.write(msg, promise);
                return;
            }
            if (!(msg instanceof Packet<?> packet) || shouldAllowHostPacket(packet, this.coordinator)) {
                ctx.write(msg, promise);
                return;
            }
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
        }

        public static boolean shouldAllowHostPacket(final Packet<?> packet, final RelayCoordinator coordinator) {
            if (coordinator.isAllowingDelegatedHostChatPacket()) {
                return true;
            }
            if (packet instanceof ServerboundCustomPayloadPacket
                || packet instanceof ServerboundConfigurationAcknowledgedPacket
                || packet instanceof ServerboundLoginAcknowledgedPacket
                || packet instanceof ServerboundChatSessionUpdatePacket
                || packet instanceof ServerboundChatAckPacket) {
                return true;
            }
            String pkg = packet.getClass().getPackageName();
            return pkg.endsWith(".protocol.configuration") || pkg.endsWith(".protocol.login");
        }
    }

    /** Injection point for Controller B's serverbound native 26.2 {@link ByteBuf}s. */
    public static final class OutboundInject extends ChannelOutboundHandlerAdapter {
        private final RelayCoordinator coordinator;

        public OutboundInject(final RelayCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public void handlerAdded(final ChannelHandlerContext ctx) {
            this.coordinator.setInjectContext(ctx);
        }

        @Override
        public void handlerRemoved(final ChannelHandlerContext ctx) {
            this.coordinator.clearInjectContextIfMatches(ctx);
        }
    }
}
