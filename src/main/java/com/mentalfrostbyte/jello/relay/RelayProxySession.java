package com.mentalfrostbyte.jello.relay;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.server.packs.repository.KnownPack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles a single Controller B connection to Host A's embedded Sigma Relay proxy across
 * {@code HANDSHAKE -> STATUS} or {@code HANDSHAKE -> LOGIN -> CONFIGURATION -> PLAY} (including seamless
 * {@code PLAY -> CONFIGURATION -> PLAY} sub-server transfers).
 */
public class RelayProxySession extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger LOGGER = LoggerFactory.getLogger("SigmaRelay");
    public static final int COMPRESSION_THRESHOLD = 256;

    static {
        SharedConstants.tryDetectVersion();
    }

    public enum Phase {
        HANDSHAKE,
        STATUS,
        LOGIN,
        CONFIGURATION,
        PLAY,
        CLOSED
    }

    private final RelayCoordinator coordinator;
    private volatile @Nullable Channel channel;
    private volatile Phase phase = Phase.HANDSHAKE;
    private volatile boolean reconfiguring;
    private volatile boolean awaitingReconfigureAck;
    private @Nullable String controllerUsername;

    public RelayProxySession(final RelayCoordinator coordinator) {
        super(true);
        this.coordinator = coordinator;
    }

    public Phase phase() {
        return this.phase;
    }

    public boolean isInPlay() {
        return this.phase == Phase.PLAY && !this.awaitingReconfigureAck && isChannelActive();
    }

    public boolean isChannelActive() {
        Channel ch = this.channel;
        return ch != null && ch.isActive() && this.phase != Phase.CLOSED;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        this.channel = ctx.channel();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        this.phase = Phase.CLOSED;
        this.coordinator.onControllerDisconnected(this);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        LOGGER.debug("Controller connection error in phase {}: {}", this.phase, cause.toString());
        this.phase = Phase.CLOSED;
        this.coordinator.onControllerDisconnected(this);
        ctx.close();
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final ByteBuf msg) {
        switch (this.phase) {
            case HANDSHAKE -> handleHandshake(ctx, msg);
            case STATUS -> handleStatus(ctx, msg);
            case LOGIN -> handleLogin(ctx, msg);
            case CONFIGURATION -> handleConfiguration(ctx, msg);
            case PLAY -> handlePlay(ctx, msg);
            case CLOSED -> {
            }
        }
    }

    // ── Handshake & Status ───────────────────────────────────────────────────────

    private void handleHandshake(final ChannelHandlerContext ctx, final ByteBuf msg) {
        Packet<?> decoded = HandshakeProtocols.SERVERBOUND.codec().decode(msg);
        if (!(decoded instanceof ClientIntentionPacket intention)) {
            ctx.close();
            return;
        }
        ClientIntent intent = intention.intention();
        if (intent == ClientIntent.STATUS) {
            this.phase = Phase.STATUS;
        } else if (intent == ClientIntent.LOGIN || intent == ClientIntent.TRANSFER) {
            int nativeProtocol = SharedConstants.getCurrentVersion().protocolVersion();
            if (intention.protocolVersion() != nativeProtocol) {
                this.phase = Phase.LOGIN;
                sendLoginDisconnect(ctx, Component.literal(
                    "Sigma Relay requires Minecraft " + SharedConstants.getCurrentVersion().name()
                        + " (protocol " + nativeProtocol + "), got protocol " + intention.protocolVersion()));
                return;
            }
            this.phase = Phase.LOGIN;
        } else {
            ctx.close();
        }
    }

    private void handleStatus(final ChannelHandlerContext ctx, final ByteBuf msg) {
        Packet<?> decoded = StatusProtocols.SERVERBOUND.codec().decode(msg);
        if (decoded instanceof ServerboundStatusRequestPacket) {
            int online = this.coordinator.isControllerConnected() ? 1 : 0;
            ServerStatus status = new ServerStatus(
                Component.literal(this.coordinator.buildCurrentMotd()),
                Optional.of(new ServerStatus.Players(1, online, List.of())),
                Optional.of(new ServerStatus.Version(
                    SharedConstants.getCurrentVersion().name(),
                    SharedConstants.getCurrentVersion().protocolVersion()
                )),
                Optional.empty(),
                false
            );
            ctx.writeAndFlush(RelayPacketIds.encodePacket(StatusProtocols.CLIENTBOUND, new ClientboundStatusResponsePacket(status)));
        } else if (decoded instanceof ServerboundPingRequestPacket ping) {
            ctx.writeAndFlush(RelayPacketIds.encodePacket(StatusProtocols.CLIENTBOUND, new ClientboundPongResponsePacket(ping.getTime())))
                .addListener(ChannelFutureListener.CLOSE);
        }
    }

    // ── Login ────────────────────────────────────────────────────────────────────

    private void handleLogin(final ChannelHandlerContext ctx, final ByteBuf msg) {
        Packet<?> decoded = LoginProtocols.SERVERBOUND.codec().decode(msg);
        if (decoded instanceof ServerboundHelloPacket hello) {
            this.controllerUsername = hello.name();
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || !RelayWorldSnapshot.isHostReadyInPlay(mc)) {
                sendLoginDisconnect(ctx, Component.literal("Sigma Relay: Host client is not currently in a world."));
                return;
            }
            if (!this.coordinator.tryClaimSession(this, hello.name())) {
                sendLoginDisconnect(ctx, Component.literal("Sigma Relay: Another controller is already connected."));
                return;
            }
            this.coordinator.ensureAttachedToCurrentConnection();

            GameProfile hostProfile = RelayWorldSnapshot.readHostProfile(mc);
            String loginName = hostProfile != null && hostProfile.name() != null ? hostProfile.name() : hello.name();
            UUID fallbackUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + loginName).getBytes(StandardCharsets.UTF_8));
            GameProfile inheritedProfile = hostProfile != null ? hostProfile : new GameProfile(fallbackUuid, loginName);

            // Send SetCompression uncompressed BEFORE installing CompressionDecoder/CompressionEncoder.
            ctx.writeAndFlush(RelayPacketIds.encodePacket(
                LoginProtocols.CLIENTBOUND,
                new ClientboundLoginCompressionPacket(COMPRESSION_THRESHOLD)
            ));
            enableCompression(ctx.pipeline(), COMPRESSION_THRESHOLD);

            ctx.writeAndFlush(RelayPacketIds.encodePacket(
                LoginProtocols.CLIENTBOUND,
                new ClientboundLoginFinishedPacket(inheritedProfile, UUID.randomUUID())
            ));
        } else if (decoded instanceof ServerboundLoginAcknowledgedPacket) {
            enterConfiguration(ctx);
        }
    }

    private static void enableCompression(final ChannelPipeline pipeline, final int threshold) {
        if (pipeline.get("decompress") == null) {
            pipeline.addAfter("splitter", "decompress", new CompressionDecoder(threshold, true));
        }
        if (pipeline.get("compress") == null) {
            pipeline.addAfter("prepender", "compress", new CompressionEncoder(threshold));
        }
    }

    private void enterConfiguration(final ChannelHandlerContext ctx) {
        this.phase = Phase.CONFIGURATION;
        ctx.writeAndFlush(RelayPacketIds.encodePacket(
            ConfigurationProtocols.CLIENTBOUND,
            new ClientboundSelectKnownPacks(List.of(KnownPack.vanilla("core")))
        ));
    }

    // ── Configuration ────────────────────────────────────────────────────────────

    private void handleConfiguration(final ChannelHandlerContext ctx, final ByteBuf msg) {
        Packet<?> decoded = ConfigurationProtocols.SERVERBOUND.codec().decode(msg);
        if (decoded instanceof ServerboundSelectKnownPacks) {
            if (this.reconfiguring) {
                // During a sub-server transfer, Host A may still be reconfiguring with the real server;
                // wait off the Netty thread until Host A re-enters PLAY with fresh registries.
                Thread waiter = new Thread(() -> sendConfigurationWhenHostReady(ctx), "Sigma-Relay-Reconfig");
                waiter.setDaemon(true);
                waiter.start();
            } else {
                sendConfigurationSnapshot(ctx);
            }
        } else if (decoded instanceof ServerboundFinishConfigurationPacket) {
            enterPlay(ctx);
        }
        // ClientInformation, CustomPayload, KeepAlive, Pong, CookieResponse in CONFIGURATION are absorbed locally.
    }

    private void sendConfigurationSnapshot(final ChannelHandlerContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            disconnectWithReason(Component.literal("Sigma Relay: Host unavailable."));
            return;
        }
        List<ByteBuf> packets = RelayWorldSnapshot.buildConfigurationPackets(mc);
        if (packets.isEmpty()) {
            disconnectWithReason(Component.literal("Sigma Relay: Host registries are not ready."));
            return;
        }
        for (ByteBuf packet : packets) {
            sendRaw(packet);
        }
        sendRaw(RelayPacketIds.encodePacket(ConfigurationProtocols.CLIENTBOUND, ClientboundFinishConfigurationPacket.INSTANCE));
    }

    private void sendConfigurationWhenHostReady(final ChannelHandlerContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline && isChannelActive()) {
            if (!this.coordinator.isServerInConfiguration() && RelayWorldSnapshot.isHostReadyInPlay(mc)) {
                List<ByteBuf> packets = RelayWorldSnapshot.buildConfigurationPackets(mc);
                if (!packets.isEmpty()) {
                    for (ByteBuf packet : packets) {
                        sendRaw(packet);
                    }
                    sendRaw(RelayPacketIds.encodePacket(
                        ConfigurationProtocols.CLIENTBOUND,
                        ClientboundFinishConfigurationPacket.INSTANCE
                    ));
                    return;
                }
            }
            try {
                Thread.sleep(200L);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        disconnectWithReason(Component.literal("Sigma Relay: Timed out waiting for host sub-server transfer."));
    }

    // ── Play & Sub-Server Reconfiguration ────────────────────────────────────────

    private void enterPlay(final ChannelHandlerContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            ctx.close();
            return;
        }
        this.reconfiguring = false;
        this.awaitingReconfigureAck = false;
        this.coordinator.beginWorldRebuild();
        this.phase = Phase.PLAY;

        ByteBuf joinGame;
        try {
            joinGame = RelayWorldSnapshot.buildJoinGamePacket(mc);
        } catch (final Throwable t) {
            LOGGER.error("Failed to build JoinGame snapshot for Controller B", t);
            this.coordinator.endWorldRebuild();
            disconnectWithReason(Component.literal("Sigma Relay: Failed to snapshot host world (" + t.getMessage() + ")"));
            return;
        }

        sendRaw(joinGame);
        this.coordinator.onControllerEnteredPlay(this);

        Thread snapshotWorker = new Thread(() -> {
            try {
                RelayWorldSnapshot.streamWorldState(mc, this::sendRaw);
            } catch (final Throwable t) {
                LOGGER.error("Error while streaming world snapshot to Controller B", t);
            } finally {
                this.coordinator.endWorldRebuild();
            }
        }, "Sigma-Relay-Snapshot");
        snapshotWorker.setDaemon(true);
        snapshotWorker.start();
    }

    private void handlePlay(final ChannelHandlerContext ctx, final ByteBuf msg) {
        int packetId = RelayPacketIds.peekPacketId(msg);
        if (this.awaitingReconfigureAck) {
            if (packetId == RelayPacketIds.C2S_CONFIGURATION_ACKNOWLEDGED) {
                this.awaitingReconfigureAck = false;
                enterConfiguration(ctx);
            }
            return;
        }

        this.coordinator.mirror().observeControllerPacket(packetId, msg);
        if (this.coordinator.tryDelegateSignedChatOrCommand(packetId, msg)) {
            return;
        }
        this.coordinator.forwardControllerPacketToServer(msg.retainedDuplicate());
    }

    /** Pulls Controller B back into {@code CONFIGURATION} when Host A transfers between sub-servers. */
    public void beginReconfigure() {
        Channel ch = this.channel;
        if (ch == null || !ch.isActive() || this.phase != Phase.PLAY || this.awaitingReconfigureAck) {
            return;
        }
        this.awaitingReconfigureAck = true;
        this.reconfiguring = true;
        ByteBuf startConfig = Unpooled.buffer(4);
        VarInt.write(startConfig, RelayPacketIds.S2C_START_CONFIGURATION);
        sendRaw(startConfig);
    }

    // ── Raw send & disconnect helpers ────────────────────────────────────────────

    public void sendRaw(final ByteBuf payload) {
        Channel ch = this.channel;
        if (ch == null || !ch.isActive()) {
            payload.release();
            return;
        }
        ch.writeAndFlush(payload);
    }

    public void disconnectWithReason(final Component reason) {
        Channel ch = this.channel;
        Phase currentPhase = this.phase;
        this.phase = Phase.CLOSED;
        if (ch == null || !ch.isActive()) {
            return;
        }
        try {
            if (currentPhase == Phase.LOGIN) {
                ch.writeAndFlush(RelayPacketIds.encodePacket(
                    LoginProtocols.CLIENTBOUND,
                    new ClientboundLoginDisconnectPacket(reason)
                )).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (currentPhase == Phase.CONFIGURATION) {
                ch.writeAndFlush(RelayPacketIds.encodePacket(
                    ConfigurationProtocols.CLIENTBOUND,
                    new ClientboundDisconnectPacket(reason)
                )).addListener(ChannelFutureListener.CLOSE);
                return;
            }
        } catch (final Throwable ignored) {
        }
        ch.close();
    }

    private void sendLoginDisconnect(final ChannelHandlerContext ctx, final Component reason) {
        this.phase = Phase.CLOSED;
        ctx.writeAndFlush(RelayPacketIds.encodePacket(
            LoginProtocols.CLIENTBOUND,
            new ClientboundLoginDisconnectPacket(reason)
        )).addListener(ChannelFutureListener.CLOSE);
    }
}
