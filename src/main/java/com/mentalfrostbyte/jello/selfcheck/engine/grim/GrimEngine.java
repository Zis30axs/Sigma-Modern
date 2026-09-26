package com.mentalfrostbyte.jello.selfcheck.engine.grim;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.event.events.CompletePredictionEvent;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserConnectEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.manager.InternalPacketListener;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.PacketSide;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.PacketEventsImplHelper;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisconnect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import com.mentalfrostbyte.jello.selfcheck.api.Placement;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngine;
import com.mentalfrostbyte.jello.selfcheck.api.WirePacket;
import io.github.retrooper.packetevents.impl.netty.BuildData;
import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;

/**
 * GrimAC judging the local player for one connection, the way it would on the server.
 *
 * <p>Every packet is shown to PacketEvents as it crossed the wire, in the server's protocol and with the client's
 * version equal to the server's - a server without ViaVersion. PacketEvents calls its pre-Via and post-Via listeners
 * in separate passes, and Grim uses both, so each packet goes through twice in the order a server's pipeline has them:
 * a client packet pre-Via then post-Via, a server packet post-Via then pre-Via. A pass that cancels the packet stops it,
 * as the server would stop it there.</p>
 *
 * <p>Grim learns when the client handled what by its transactions. The ones it writes are turned into SelfDetection
 * transactions (real pings the client answers in order, see {@code SelfCheckSession}), placed where Grim wrote them:
 * ahead of the packet being sent when written while handling it, behind it when written in a task after it was sent.
 * Grim counts a transaction as sent when it sees its own ping go out, so that send event is fired here; the client's
 * answer comes back as the pong Grim expects. The server's own transactions are never shown to Grim - their ids share
 * Grim's number space (a server anticheat may use any 16-bit value) and would be taken for Grim's.</p>
 *
 * <p>Nothing else Grim writes reaches the client: a disconnect becomes a report; setbacks, resyncs and the like were
 * already cut off in the ported sources (see SELFCHECK_PORTING.md) and anything left is dropped and counted.</p>
 */
final class GrimEngine implements SelfCheckEngine {

    private static final String COMMAND = "grim";

    private final EngineContext context;
    private final ClientVersion version;
    private final EmbeddedChannel channel;
    private final PacketEventsAPI<BuildData> api;
    private final SigmaScheduler scheduler;
    private final GrimChat chat;
    private final SigmaSenders senders;
    private final SigmaPlayers.Factory players;
    private final SigmaGrimLoader loader;
    private final SigmaTickEnd tickEnd = new SigmaTickEnd();
    private final Map<Integer, Integer> transactions = new HashMap<>();
    private final Set<String> droppedKinds = new HashSet<>();
    private @Nullable User user;
    private Placement placement = Placement.NOW;
    private boolean loggedIn;
    private volatile boolean closed;
    private long hiddenServerTransactions;
    private long droppedWrites;
    private long answeredForTheClient;
    // The positive signal: silence from Grim means nothing unless it was actually predicting movements.
    private long predictions;
    private double largestOffset;
    private long flags;
    private long lastStatus = System.nanoTime();

    private GrimEngine(final EngineContext context, final ClientVersion version) {
        this.context = context;
        this.version = version;
        EventLoop network = context.eventLoop();
        // PacketEvents' view of the player's channel. Grim schedules work on its event loop, which must be the
        // connection's network thread, where every packet is handled. Nothing is ever written to it, and the one place
        // Grim closed it on a kick is a MODIFIED for porting site (PacketPluginMessage) - SelfDetection keeps judging.
        this.channel = new EmbeddedChannel() {
            // Registration inside EmbeddedChannel's constructor needs its own loop; this field is set right after it
            // (not final: a final field initialised with a constant would be read as that constant, even during super()).
            private volatile boolean constructed = true;

            @Override
            public EventLoop eventLoop() {
                return this.constructed ? network : super.eventLoop();
            }
        };
        this.api = LocalPacketEvents.create(serverVersion(version), this);
        this.scheduler = new SigmaScheduler("Sigma SelfCheck Grim", failure -> context.log("[scheduler] task failed: " + failure));
        this.chat = new GrimChat(context);
        this.senders = new SigmaSenders(this.chat);
        this.players = new SigmaPlayers.Factory(this);
        PacketEvents.setAPI(this.api);
        this.loader = new SigmaGrimLoader(context, this.api, this.scheduler, this.players, this.senders);
    }

    static GrimEngine start(final EngineContext context) {
        SigmaGrim.bind(context);
        GrimEngine engine = new GrimEngine(context, ClientVersion.getById(context.protocolVersion()));
        engine.boot();
        return engine;
    }

    /** The newest server version that speaks {@code version}'s protocol, e.g. 1.8.8 for 1.8.x. */
    static ServerVersion serverVersion(final ClientVersion version) {
        ServerVersion match = version.toServerVersion();
        for (ServerVersion candidate : ServerVersion.values()) {
            if (candidate.getProtocolVersion() == version.getProtocolVersion()) {
                match = candidate;
            }
        }
        return match;
    }

    private void boot() {
        GrimAPI.INSTANCE.load(this.loader, this.tickEnd);
        GrimAPI.INSTANCE.getCommandService().registerCommands();
        GrimAPI.INSTANCE.start();
        // The builder only registers PacketEvents' post-Via state tracker; the pre-Via one keeps the pre-Via
        // connection state (which the pre-Via pass reads) in step.
        this.api.getEventManager().registerListener(new InternalPacketListener(PacketListenerPriority.LOWEST, true));
        GrimAPI.INSTANCE.getEventBus().get(FlagEvent.class).onFlag(this.loader.getPlugin(), (grimUser, check, verbose, cancelled) -> {
            if (!cancelled) {
                this.flags++;
                this.context.verdicts().flag(check.getCheckName(), check.getViolations() + 1, verbose);
            }
            return cancelled;
        });
        GrimAPI.INSTANCE.getEventBus().get(CompletePredictionEvent.class).onCompletePrediction(this.loader.getPlugin(),
                (grimUser, check, offset, cancelled) -> {
                    this.predictions++;
                    this.largestOffset = Math.max(this.largestOffset, offset);
                    return cancelled;
                });

        User player = new User(this.channel, ConnectionState.HANDSHAKING, null, new UserProfile(null, null));
        this.api.getProtocolManager().setUser(this.channel, player);
        this.user = player;
        this.api.getEventManager().callEvent(new UserConnectEvent(player));
        this.context.registerCommand(COMMAND);
        this.context.log("GrimAC ready for " + this.context.serverAddress() + " as " + this.version.getReleaseName());
    }

    EmbeddedChannel channel() {
        return this.channel;
    }

    GrimChat chat() {
        return this.chat;
    }

    SigmaSenders senders() {
        return this.senders;
    }

    boolean isClosed() {
        return this.closed;
    }

    // ------------------------------------------------------------------------------------------ packets

    @Override
    public void onClientbound(final WirePacket packet) {
        User player = this.user;
        if (player == null) {
            return;
        }
        ByteBuf wire = packet.buffer();
        PacketTypeCommon type = type(PacketSide.SERVER, player.getEncoderState(), wire);
        if (this.isServerTransaction(type, wire)) {
            this.hiddenServerTransactions++;
            return;
        }
        this.dispatchClientbound(player, wire);
        if (!this.loggedIn && type == PacketType.Play.Server.JOIN_GAME) {
            this.login(player);
        }
    }

    @Override
    public void onServerbound(final WirePacket packet) {
        User player = this.user;
        if (player == null) {
            return;
        }
        ByteBuf wire = packet.buffer();
        PacketTypeCommon type = type(PacketSide.CLIENT, player.getDecoderState(), wire);
        if (this.isServerTransaction(type, wire)) {
            this.hiddenServerTransactions++;
            return;
        }
        this.dispatchServerbound(player, wire);
    }

    private void dispatchClientbound(final User player, final ByteBuf wire) {
        Placement saved = this.placement;
        ByteBuf postVia = wire.copy();
        ByteBuf preVia = null;
        try {
            this.placement = Placement.BEFORE_CURRENT;
            PacketSendEvent post = PacketEventsImplHelper.handleClientBoundPacket(this.channel, player, null, postVia, true);
            PacketSendEvent pre = null;
            if (post == null || !post.isCancelled()) {
                preVia = wire.copy();
                pre = PacketEventsImplHelper.handleClientBoundPacket(this.channel, player, null, preVia, false);
            }
            this.placement = Placement.AFTER_CURRENT;
            runTasksAfterSend(post);
            runTasksAfterSend(pre);
        } catch (Exception e) {
            throw new IllegalStateException("PacketEvents could not handle a server packet", e);
        } finally {
            this.placement = saved;
            postVia.release();
            if (preVia != null) {
                preVia.release();
            }
        }
    }

    private void dispatchServerbound(final User player, final ByteBuf wire) {
        ByteBuf preVia = wire.copy();
        ByteBuf postVia = null;
        try {
            PacketReceiveEvent pre = PacketEventsImplHelper.handleServerBoundPacket(this.channel, player, null, preVia, false);
            if (pre == null || !pre.isCancelled()) {
                postVia = wire.copy();
                PacketEventsImplHelper.handleServerBoundPacket(this.channel, player, null, postVia, true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("PacketEvents could not handle a client packet", e);
        } finally {
            preVia.release();
            if (postVia != null) {
                postVia.release();
            }
        }
    }

    private static void runTasksAfterSend(final @Nullable PacketSendEvent event) {
        if (event != null && event.hasTasksAfterSend()) {
            for (Runnable task : event.getTasksAfterSend()) {
                task.run();
            }
        }
    }

    /** The packet's type, read from the id at the front of the buffer without moving its reader index. */
    private @Nullable PacketTypeCommon type(final PacketSide side, final ConnectionState state, final ByteBuf wire) {
        int id = readVarInt(wire, wire.readerIndex());
        return id < 0 ? null : PacketType.getById(side, state, this.version, id);
    }

    /**
     * A transaction that belongs to the server, not to this Grim: every ping and pong on the wire (ours never touch
     * it), and window confirmations for window 0, which is where pre-1.17 transactions live.
     */
    private boolean isServerTransaction(final @Nullable PacketTypeCommon type, final ByteBuf wire) {
        if (type == PacketType.Play.Server.PING || type == PacketType.Play.Client.PONG
                || type == PacketType.Configuration.Server.PING || type == PacketType.Configuration.Client.PONG) {
            return true;
        }
        if (type == PacketType.Play.Server.WINDOW_CONFIRMATION || type == PacketType.Play.Client.WINDOW_CONFIRMATION) {
            int start = wire.readerIndex();
            int idLength = varIntLength(wire, start);
            return idLength > 0 && wire.readableBytes() > idLength && wire.getByte(start + idLength) == 0;
        }
        return false;
    }

    private void login(final User player) {
        UUID uuid = player.getUUID();
        String name = player.getProfile().getName();
        if (uuid == null || name == null) {
            return;
        }
        SigmaPlayers.Native local = new SigmaPlayers.Native(uuid, name);
        this.players.setLocal(local);
        this.api.getEventManager().callEvent(new UserLoginEvent(player, local));
        this.loggedIn = true;
        GrimPlayer grim = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(player);
        this.context.log(grim != null
                ? "checking " + name + " (" + grim.getClientVersion().getReleaseName() + ")"
                : "logged in as " + name + " but Grim is not tracking the player - nothing will be checked");
        // Said in the chat too, so the player knows on joining whether they are being checked at all: Grim is quiet
        // while nothing fails, and a server that runs its own Grim fills the chat with alerts that look like ours.
        this.chat.send(grim != null
                ? Component.text()
                        .append(Component.text("[SelfCheck] ", NamedTextColor.AQUA))
                        .append(Component.text("GrimAC is checking you (" + grim.getClientVersion().getReleaseName() + "). ", NamedTextColor.GREEN))
                        .append(Component.text(this.context.commandPrefix() + "grim help", NamedTextColor.WHITE)
                                .clickEvent(ClickEvent.suggestCommand("/grim help"))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to put it in the chat box"))))
                        .append(Component.text(" lists its commands. Nothing it does reaches the server.", NamedTextColor.GRAY))
                        .build()
                : Component.text()
                        .append(Component.text("[SelfCheck] ", NamedTextColor.AQUA))
                        .append(Component.text("GrimAC started but is not tracking you; nothing is being checked.", NamedTextColor.RED))
                        .build());
    }

    // ------------------------------------------------------------------------------------------ what Grim writes

    /** Everything Grim writes to the player arrives here. Runs where netty would encode it: on the network thread. */
    void grimWrites(final PacketWrapper<?> wrapper) {
        if (this.closed) {
            return;
        }
        if (!this.context.eventLoop().inEventLoop()) {
            this.context.eventLoop().execute(() -> this.grimWrites(wrapper));
            return;
        }

        Integer transaction = grimTransactionId(wrapper);
        if (transaction != null) {
            this.sendTransaction(transaction, wrapper);
            return;
        }
        Component reason = disconnectReason(wrapper);
        if (reason != null) {
            this.context.verdicts().disconnect(PlainTextComponentSerializer.plainText().serialize(reason));
            return;
        }
        this.droppedWrites++;
        if (this.droppedKinds.add(wrapper.getClass().getSimpleName())) {
            this.context.log("not delivered to the client (first of its kind): " + wrapper.getClass().getSimpleName());
        }
    }

    private static @Nullable Integer grimTransactionId(final PacketWrapper<?> wrapper) {
        if (wrapper instanceof WrapperPlayServerPing ping) {
            return ping.getId();
        }
        if (wrapper instanceof WrapperPlayServerWindowConfirmation confirmation && confirmation.getWindowId() == 0) {
            return (int) confirmation.getActionId();
        }
        return null;
    }

    private static @Nullable Component disconnectReason(final PacketWrapper<?> wrapper) {
        if (wrapper instanceof WrapperPlayServerDisconnect disconnect) {
            return disconnect.getReason();
        }
        if (wrapper instanceof WrapperConfigServerDisconnect disconnect) {
            return disconnect.getReason();
        }
        if (wrapper instanceof WrapperLoginServerDisconnect disconnect) {
            return disconnect.getReason();
        }
        return null;
    }

    private void sendTransaction(final int grimId, final PacketWrapper<?> ping) {
        User player = this.user;
        if (player == null) {
            return;
        }
        int wireId = this.context.sendTransaction(this.placement);
        // Grim counts a transaction as sent when it sees its own ping go out (PacketPingListener#onSendTransaction).
        for (Object encoded : this.api.getProtocolManager().transformWrappers(ping, this.channel, true)) {
            ByteBuf buffer = (ByteBuf) encoded;
            try {
                this.dispatchClientbound(player, buffer);
            } finally {
                ReferenceCountUtil.release(buffer);
            }
        }
        if (wireId >= 0) {
            this.transactions.put(wireId, grimId);
        } else {
            this.answer(grimId); // no id to be had: the client "answers" at once rather than never
        }
    }

    @Override
    public void onTransactionAck(final int id) {
        Integer grimId = this.transactions.remove(id);
        if (grimId != null) {
            this.answer(grimId);
        }
    }

    @Override
    public void onTransactionDropped(final int id) {
        Integer grimId = this.transactions.remove(id);
        if (grimId != null) {
            // The ping never reached the client (it was changing phase). An unanswered transaction would stall Grim's
            // clock and end in a timeout kick; answering it now only makes that one moment look a little laggier.
            this.answeredForTheClient++;
            this.answer(grimId);
        }
    }

    /** Feeds Grim the client's answer to its transaction {@code grimId}, as the wire would carry it. */
    private void answer(final int grimId) {
        User player = this.user;
        if (player == null || player.getDecoderState() != ConnectionState.PLAY) {
            return;
        }
        PacketWrapper<?> pong = this.version.isNewerThanOrEquals(ClientVersion.V_1_17)
                ? new WrapperPlayClientPong(grimId)
                : new WrapperPlayClientWindowConfirmation(0, (short) grimId, true);
        for (Object encoded : this.api.getProtocolManager().transformWrappers(pong, this.channel, false)) {
            ByteBuf buffer = (ByteBuf) encoded;
            try {
                this.dispatchServerbound(player, buffer);
            } finally {
                ReferenceCountUtil.release(buffer);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ the rest

    @Override
    public void onInboundBatchEnd() {
        this.tickEnd.endOfBatch();
        long now = System.nanoTime();
        if (now - this.lastStatus >= 30_000_000_000L) {
            this.lastStatus = now;
            this.context.log(this.status());
        }
    }

    /** Where Grim stands with the player - the first thing to read when it predicts nothing. */
    String playerState() {
        User player = this.user;
        GrimPlayer grim = player == null ? null : GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(player);
        if (grim == null) {
            return "not tracked";
        }
        var setbacks = grim.getSetbackTeleportUtil();
        var required = setbacks.getRequiredSetBack();
        return String.format(Locale.ROOT,
                "at %.2f %.2f %.2f, spawn teleport accepted %s, required setback %s, %d teleports pending, in unloaded chunk %s, "
                        + "transactions sent %d / received %d, disabled %s, gamemode %s, flying %s, platform player %s",
                grim.x, grim.y, grim.z, setbacks.hasAcceptedSpawnTeleport,
                required == null ? "none" : (required.isComplete() ? "complete" : "pending"),
                setbacks.pendingTeleports.size(), setbacks.insideUnloadedChunk(),
                grim.lastTransactionSent.get(), grim.lastTransactionReceived.get(), grim.disableGrim, grim.gamemode, grim.isFlying,
                grim.platformPlayer != null);
    }

    /** One line on what Grim has been doing, for the log. */
    String status() {
        return String.format(Locale.ROOT, "%d movements predicted (largest offset %.5f), %d flags, %d server transactions hidden, "
                        + "%d answers given for the client, %d writes not delivered",
                this.predictions, this.largestOffset, this.flags, this.hiddenServerTransactions, this.answeredForTheClient, this.droppedWrites);
    }

    @Override
    public void onCommand(final String root, final String arguments) {
        User player = this.user;
        GrimPlayer grim = player == null ? null : GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(player);
        Sender sender = grim != null && grim.platformPlayer != null ? grim.platformPlayer.getSender() : null;
        if (sender == null) {
            SigmaPlayers.Native local = this.players.local();
            sender = local != null ? this.senders.wrap(local) : this.senders.console();
        }
        this.loader.execute(sender, arguments.isEmpty() ? COMMAND : COMMAND + " " + arguments);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        User player = this.user;
        this.context.log("player state: " + this.playerState()); // before the disconnect below forgets the player
        try {
            if (player != null) {
                this.api.getEventManager().callEvent(new UserDisconnectEvent(player));
            }
            GrimAPI.INSTANCE.stop();
        } finally {
            this.scheduler.shutdown();
            this.context.log("GrimAC stopped: " + this.status());
        }
    }

    // ------------------------------------------------------------------------------------------ varints

    /** The varint at {@code index}, or -1 if it is cut off or longer than five bytes. */
    static int readVarInt(final ByteBuf buffer, final int index) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (index + i >= buffer.writerIndex()) {
                return -1;
            }
            byte b = buffer.getByte(index + i);
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return -1;
    }

    /** How many bytes the varint at {@code index} takes, or -1 if it is cut off or too long. */
    static int varIntLength(final ByteBuf buffer, final int index) {
        for (int i = 0; i < 5; i++) {
            if (index + i >= buffer.writerIndex()) {
                return -1;
            }
            if ((buffer.getByte(index + i) & 0x80) == 0) {
                return i + 1;
            }
        }
        return -1;
    }
}
