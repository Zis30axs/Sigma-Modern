package com.mentalfrostbyte.jello.relay;

import com.mentalfrostbyte.Client;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central coordinator for Sigma Relay: owns the embedded {@link RelayProxyServer}, tracks Host A's
 * active {@link Connection} channel and Controller B's {@link RelayProxySession}, queues live {@code S -> A}
 * packets during initial world reconstruction, handles sub-server {@code StartConfiguration} transitions,
 * and delegates Controller B's chat/commands through Host A's Mojang signing session when needed.
 */
public final class RelayCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger("SigmaRelay");
    private static final RelayCoordinator INSTANCE = new RelayCoordinator();
    private static final int PENDING_MAX = 4096;

    private final RelayControllerMirror mirror = new RelayControllerMirror();
    private final List<ByteBuf> pendingToController = new ArrayList<>();
    private final AtomicLong s2bPackets = new AtomicLong();
    private final AtomicLong b2sPackets = new AtomicLong();
    private final AtomicInteger delegatedHostChatDepth = new AtomicInteger();

    private @Nullable RelayProxyServer proxyServer;
    private volatile @Nullable Channel hostChannel;
    private volatile @Nullable ChannelHandlerContext injectContext;
    private volatile @Nullable RelayProxySession activeSession;
    private volatile @Nullable String controllerName;
    private volatile boolean rebuildingWorld;
    private volatile boolean serverInConfiguration;
    private volatile boolean allowLan = true;

    private RelayCoordinator() {
    }

    public static RelayCoordinator get() {
        return INSTANCE;
    }

    public RelayControllerMirror mirror() {
        return this.mirror;
    }

    // ── Proxy lifecycle ──────────────────────────────────────────────────────────

    public synchronized boolean startProxy(final int port, final boolean allowLan) {
        if (this.proxyServer != null && this.proxyServer.isRunning()
            && this.proxyServer.port() == port && this.allowLan == allowLan) {
            return true;
        }
        stopProxy();
        this.allowLan = allowLan;
        RelayProxyServer server = new RelayProxyServer(port, allowLan, this);
        try {
            server.start();
            this.proxyServer = server;
            ensureAttachedToCurrentConnection();
            notifyHostChat(Component.literal("[Sigma Relay] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(isZh()
                    ? "代理已启动于 " + (allowLan ? "0.0.0.0:" : "127.0.0.1:") + server.port()
                    : "Listening on " + (allowLan ? "0.0.0.0:" : "127.0.0.1:") + server.port())
                    .withStyle(ChatFormatting.GREEN)));
            return true;
        } catch (final Exception bindFailure) {
            LOGGER.error("Failed to start Sigma Relay proxy on port {}", port, bindFailure);
            notifyHostChat(Component.literal("[Sigma Relay] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal((isZh() ? "端口绑定失败 (" + port + ")：" : "Failed to bind port " + port + ": ")
                    + bindFailure.getMessage()).withStyle(ChatFormatting.RED)));
            return false;
        }
    }

    public synchronized void stopProxy() {
        kickController(isZh() ? "宿主已关闭 Sigma Relay" : "Host stopped Sigma Relay");
        RelayProxyServer server = this.proxyServer;
        this.proxyServer = null;
        if (server != null) {
            server.stop();
        }
        detachHostChannel();
        this.serverInConfiguration = false;
        this.mirror.reset();
    }

    public synchronized boolean isProxyRunning() {
        return this.proxyServer != null && this.proxyServer.isRunning();
    }

    public synchronized int boundPort() {
        return this.proxyServer != null ? this.proxyServer.port() : -1;
    }

    public boolean isAllowLan() {
        return this.allowLan;
    }

    // ── Host A channel attachment ────────────────────────────────────────────────

    public void ensureAttachedToCurrentConnection() {
        if (!isProxyRunning()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return;
        }
        Connection connection = listener.getConnection();
        if (connection != null && connection.channel != null && connection.channel.isOpen()) {
            if (this.hostChannel != connection.channel
                || connection.channel.pipeline().get(RelayChannelBridge.HANDLER_INBOUND) == null) {
                RelayChannelBridge.install(connection.channel, this);
            }
        }
    }

    public void attachConnectionChannel(final Channel channel) {
        if (channel != null && isProxyRunning()) {
            this.serverInConfiguration = false;
            RelayChannelBridge.install(channel, this);
        }
    }

    public void detachHostChannel() {
        Channel ch = this.hostChannel;
        this.hostChannel = null;
        this.injectContext = null;
        if (ch != null) {
            RelayChannelBridge.uninstall(ch, this);
        }
    }

    void setHostChannel(final Channel channel) {
        this.hostChannel = channel;
    }

    void clearHostChannelIfMatches(final Channel channel) {
        if (this.hostChannel == channel) {
            this.hostChannel = null;
            this.injectContext = null;
            // If Host A disconnected from the real server completely, disconnect Controller B cleanly too.
            kickController(isZh() ? "宿主已从目标服务器断开" : "Host disconnected from target server");
        }
    }

    void setInjectContext(final ChannelHandlerContext ctx) {
        this.injectContext = ctx;
    }

    void clearInjectContextIfMatches(final ChannelHandlerContext ctx) {
        if (this.injectContext == ctx) {
            this.injectContext = null;
        }
    }

    // ── Controller B session & takeover state ────────────────────────────────────

    public synchronized boolean tryClaimSession(final RelayProxySession session, final String controllerUsername) {
        RelayProxySession current = this.activeSession;
        if (current != null && current != session && current.isChannelActive()) {
            return false;
        }
        this.activeSession = session;
        this.controllerName = controllerUsername;
        this.s2bPackets.set(0L);
        this.b2sPackets.set(0L);
        return true;
    }

    public synchronized void onControllerEnteredPlay(final RelayProxySession session) {
        this.activeSession = session;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                LocalPlayer player = mc.player;
                if (player != null) {
                    this.mirror.seedFromHost(player);
                }
            });
        }
        String who = this.controllerName != null ? this.controllerName : "Controller";
        notifyHostChat(Component.literal("[Sigma Relay] ").withStyle(ChatFormatting.AQUA)
            .append(Component.literal(isZh()
                ? who + " 已接管控制权（宿主进入实时观战）"
                : who + " took control (host mirroring live)").withStyle(ChatFormatting.GREEN)));
    }

    public synchronized void onControllerDisconnected(final RelayProxySession session) {
        if (this.activeSession != session) {
            return;
        }
        String who = this.controllerName;
        this.activeSession = null;
        this.controllerName = null;
        this.rebuildingWorld = false;
        releasePendingLocked();
        this.mirror.reset();
        if (who != null) {
            notifyHostChat(Component.literal("[Sigma Relay] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(isZh()
                    ? who + " 已断开，宿主恢复控制权"
                    : who + " disconnected — host control restored").withStyle(ChatFormatting.YELLOW)));
        }
    }

    public synchronized boolean kickController(final String reason) {
        RelayProxySession session = this.activeSession;
        if (session == null) {
            return false;
        }
        this.activeSession = null;
        this.controllerName = null;
        this.rebuildingWorld = false;
        releasePendingLocked();
        this.mirror.reset();
        session.disconnectWithReason(Component.literal(reason));
        return true;
    }

    public boolean isControllerConnected() {
        RelayProxySession session = this.activeSession;
        return session != null && session.isChannelActive();
    }

    /** True when Controller B is in PLAY and Host A is in PLAY (so Host A's packets should be suppressed). */
    public boolean isControllerActivelyControlling() {
        RelayProxySession session = this.activeSession;
        return session != null && session.isInPlay() && !this.serverInConfiguration;
    }

    public @Nullable String controllerName() {
        return isControllerConnected() ? this.controllerName : null;
    }

    public long relayedServerToControllerPackets() {
        return this.s2bPackets.get();
    }

    public long relayedControllerToServerPackets() {
        return this.b2sPackets.get();
    }

    // ── Snapshot rebuild & sub-server StartConfiguration ─────────────────────────

    public synchronized void beginWorldRebuild() {
        this.rebuildingWorld = true;
    }

    public synchronized void endWorldRebuild() {
        if (!this.rebuildingWorld) {
            return;
        }
        this.rebuildingWorld = false;
        flushPendingLocked();
    }

    public void onServerStartConfiguration() {
        this.serverInConfiguration = true;
        RelayProxySession session;
        synchronized (this) {
            this.rebuildingWorld = false;
            releasePendingLocked();
            session = this.activeSession;
        }
        if (session != null && session.isChannelActive()) {
            session.beginReconfigure();
        }
    }

    public void onServerReenteredPlay() {
        this.serverInConfiguration = false;
    }

    public boolean isServerInConfiguration() {
        return this.serverInConfiguration;
    }

    /** Forwards a retained {@code S -> A} native 26.2 PLAY packet {@link ByteBuf} to Controller B. */
    public void forwardServerPacketToController(final ByteBuf retainedPacket) {
        RelayProxySession session = this.activeSession;
        if (session == null || !session.isChannelActive()) {
            retainedPacket.release();
            return;
        }
        if (this.rebuildingWorld || !session.isInPlay()) {
            synchronized (this) {
                RelayProxySession recheck = this.activeSession;
                if (recheck == null || !recheck.isChannelActive()) {
                    retainedPacket.release();
                    return;
                }
                if (this.rebuildingWorld || !recheck.isInPlay()) {
                    if (this.pendingToController.size() < PENDING_MAX) {
                        this.pendingToController.add(retainedPacket);
                    } else {
                        retainedPacket.release();
                    }
                    return;
                }
            }
        }
        session.sendRaw(retainedPacket);
        this.s2bPackets.incrementAndGet();
    }

    private void flushPendingLocked() {
        RelayProxySession session = this.activeSession;
        if (this.rebuildingWorld || session == null || !session.isInPlay()) {
            return;
        }
        for (ByteBuf buf : this.pendingToController) {
            if (session.isChannelActive()) {
                session.sendRaw(buf);
                this.s2bPackets.incrementAndGet();
            } else {
                buf.release();
            }
        }
        this.pendingToController.clear();
    }

    private void releasePendingLocked() {
        for (ByteBuf buf : this.pendingToController) {
            buf.release();
        }
        this.pendingToController.clear();
    }

    /** Injects a retained {@code B -> P} native 26.2 PLAY packet {@link ByteBuf} into Host A's outbound channel. */
    public void forwardControllerPacketToServer(final ByteBuf retainedPacket) {
        ChannelHandlerContext ctx = this.injectContext;
        if (ctx == null || !ctx.channel().isActive()) {
            ensureAttachedToCurrentConnection();
            ctx = this.injectContext;
            if (ctx == null || !ctx.channel().isActive()) {
                retainedPacket.release();
                return;
            }
        }
        ctx.writeAndFlush(retainedPacket);
        this.b2sPackets.incrementAndGet();
    }

    // ── Signed chat / command delegation via Host A's Mojang session ──────────────

    /**
     * When Host A is on an online-mode server with an active Mojang chat signing session, Controller B's
     * unsigned chat/command packets would be rejected by {@code enforce-secure-profile}. Delegating them
     * through {@link ClientPacketListener#sendRawChat} / {@link ClientPacketListener#sendCommand} signs
     * them with Host A's real key pair seamlessly.
     */
    public boolean tryDelegateSignedChatOrCommand(final int packetId, final ByteBuf rawPacket) {
        if (packetId != RelayPacketIds.C2S_CHAT
            && packetId != RelayPacketIds.C2S_CHAT_COMMAND
            && packetId != RelayPacketIds.C2S_CHAT_COMMAND_SIGNED) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return false;
        }
        ClientPacketListener listener = mc.getConnection();
        if (listener == null || !listener.hasVerifiableChatSession()) {
            return false;
        }
        String text = RelayControllerMirror.peekChatOrCommandText(packetId, rawPacket);
        if (text == null) {
            return false;
        }
        boolean isChat = packetId == RelayPacketIds.C2S_CHAT;
        mc.execute(() -> {
            ClientPacketListener activeListener = mc.getConnection();
            if (activeListener == null) {
                return;
            }
            this.delegatedHostChatDepth.incrementAndGet();
            try {
                if (isChat) {
                    activeListener.sendRawChat(text);
                } else {
                    activeListener.sendCommand(text);
                }
                // Flush immediately so delegated chat leaves on the current tick while whitelisted.
                activeListener.getConnection().flushChannel();
            } finally {
                // Decrement on the channel's eventLoop so sendPacket's async execute still sees depth > 0!
                Channel ch = activeListener.getConnection().channel;
                if (ch != null && ch.isOpen()) {
                    ch.eventLoop().execute(this.delegatedHostChatDepth::decrementAndGet);
                } else {
                    this.delegatedHostChatDepth.decrementAndGet();
                }
            }
        });
        this.b2sPackets.incrementAndGet();
        return true;
    }

    public boolean isAllowingDelegatedHostChatPacket() {
        return this.delegatedHostChatDepth.get() > 0;
    }

    // ── Status / MOTD helpers ────────────────────────────────────────────────────

    public String describeHostTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return "Offline";
        }
        if (mc.hasSingleplayerServer()) {
            return "Singleplayer";
        }
        ServerData data = mc.getCurrentServer();
        if (data != null && data.ip != null && !data.ip.isBlank()) {
            return data.ip;
        }
        ClientPacketListener listener = mc.getConnection();
        if (listener != null) {
            return "Connected";
        }
        return "Idle";
    }

    public String describeHostPlayerName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getUser() != null) {
            return mc.getUser().getName();
        }
        return "Sigma";
    }

    public String buildCurrentMotd() {
        return RelayLanBroadcast.buildMotd(describeHostPlayerName(), describeHostTarget(), isControllerConnected());
    }

    private static boolean isZh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return false;
        }
        try {
            Client client = Client.getInstance();
            if (client != null && client.getTranslations() != null) {
                return client.getTranslations().selected() == com.mentalfrostbyte.jello.lang.ClientLanguage.ZH_CN;
            }
        } catch (final RuntimeException ignored) {
        }
        return mc.options != null && mc.options.languageCode != null && mc.options.languageCode.startsWith("zh");
    }

    private static void notifyHostChat(final Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            if (mc.gui != null && mc.player != null) {
                com.mentalfrostbyte.jello.util.text.ChatUtil.print(message);
            } else {
                LOGGER.info(message.getString());
            }
        });
    }
}
