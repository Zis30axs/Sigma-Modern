package com.mentalfrostbyte.jello.selfcheck.host;

import com.mentalfrostbyte.jello.selfcheck.api.Direction;
import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import com.mentalfrostbyte.jello.selfcheck.api.Placement;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngine;
import com.mentalfrostbyte.jello.selfcheck.api.Verdicts;
import com.mentalfrostbyte.jello.selfcheck.api.WirePacket;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckPipeline.PingMarker;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The self-check for one connection: the engines judging it and the traffic they are shown.
 *
 * <p>Everything here runs on the connection's network thread except {@link #enginesReady} being scheduled from the
 * loader thread and commands arriving from the game thread; both hop onto the network thread first. The engines
 * therefore see one strictly ordered stream - server packets, client packets, and the answers to their own
 * transactions, interleaved exactly as they crossed the taps.</p>
 *
 * <p>Until the engines have started, packets from the server are held back from the client as well as from the
 * engines, and released in order once they are ready. An engine is only accurate about what it watched happen: had the
 * client handled the login and the first chunks while the engines were still starting, their transactions could no
 * longer go where they belong, and a burst of held-back movement would look like a timer cheat. The cost is that
 * joining waits for the engines (a few seconds for Grim), and never longer than {@link #START_TIMEOUT_SECONDS}.</p>
 *
 * <p>Nothing an engine does can reach the real connection: an engine that throws loses that one call. Each failure
 * adds one to the engine's failure score and each successful call takes {@link #FAILURE_DECAY} off it; an engine
 * whose score reaches {@link #FAILURE_LIMIT} - one that fails far more often than once in twenty calls - is switched
 * off for the rest of the connection.</p>
 */
public final class SelfCheckSession {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How much traffic may be held back while the engines start before the session gives up and lets it through. */
    static final long BACKLOG_LIMIT = 128L << 20;
    /** How long the client may be kept waiting for the engines before the session gives up and lets everything through. */
    static final long START_TIMEOUT_SECONDS = 15;
    /** The first version with a configuration phase on the wire. */
    static final int PROTOCOL_1_20_2 = 764;
    static final double FAILURE_LIMIT = 10.0;
    static final double FAILURE_DECAY = 0.05;

    private final Channel channel;
    private final Target target;
    private final ServerRoot root;
    private final SelfCheckOutput output;
    private final Recording.@Nullable Writer recorder;
    private final TransactionPool<Slot> pool = new TransactionPool<>();
    private final Map<String, Slot> commandRoots = new ConcurrentHashMap<>();
    private final String commandPrefix;
    /** Owns the markers sent while recording; it has no engine. */
    private final Slot markers;

    private final List<Slot> slots = new ArrayList<>();
    private final ArrayDeque<Held> backlog = new ArrayDeque<>();
    private long backlogBytes;
    private final long startedAt = System.nanoTime();
    private boolean ready;
    private volatile boolean closed;
    private boolean recorderFailed;

    private @Nullable ChannelHandlerContext inbound;
    private @Nullable List<Integer> before;
    private @Nullable List<Integer> after;

    /** Held back while the engines start: a server packet itself (not yet given to the client), or a copy of one sent. */
    private record Held(Direction direction, long nanoTime, @Nullable ByteBuf inbound, byte @Nullable [] outbound) {
    }

    public SelfCheckSession(final Channel channel, final Target target, final ServerRoot root, final SelfCheckOutput output,
                            final Recording.@Nullable Writer recorder) {
        this(channel, target, root, output, recorder, ".");
    }

    public SelfCheckSession(final Channel channel, final Target target, final ServerRoot root, final SelfCheckOutput output,
                            final Recording.@Nullable Writer recorder, final String commandPrefix) {
        this.channel = channel;
        this.target = target;
        this.root = root;
        this.output = output;
        this.recorder = recorder;
        this.commandPrefix = commandPrefix;
        this.markers = new Slot("marker", root.root(), () -> {
        });
    }

    public Target target() {
        return this.target;
    }

    public boolean isClosed() {
        return this.closed;
    }

    /** A context for an engine called {@code id}; the engine is attached with {@link Slot#attach} once created. */
    public Slot newSlot(final String id, final Path dataFolder, final AutoCloseable loader) {
        return new Slot(id, dataFolder, loader);
    }

    /**
     * The engines finished starting. Called from the loader thread; the rest happens on the network thread, where the
     * traffic held back meanwhile is delivered in its original order before anything newer.
     */
    public void enginesReady(final List<Slot> started) {
        this.enginesReady(started, "No engine accepted " + this.target.protocolName() + "; nothing is being checked on this server.");
    }

    /** {@link #enginesReady(List)}, saying {@code whenNone} to the player if not one engine started. */
    public void enginesReady(final List<Slot> started, final String whenNone) {
        this.onNetworkThread(() -> {
            if (this.closed) {
                started.forEach(Slot::shutDown);
                return;
            }
            this.slots.addAll(started);
            this.root.log(String.format(Locale.ROOT, "[SelfCheck] engines ready after %d ms; %d packets (%d KB) were held back meanwhile",
                    (System.nanoTime() - this.startedAt) / 1_000_000, this.backlog.size(), this.backlogBytes / 1024));
            this.ready = true;
            // Everything held back, in the order it crossed the taps - now watched as it happens: a server packet is
            // shown to the engines and then given to the client, exactly as if it had just arrived.
            ChannelHandlerContext ctx = this.inbound;
            Held held;
            while ((held = this.backlog.poll()) != null) {
                if (held.inbound() != null && ctx != null) {
                    this.process(ctx, System.nanoTime(), held.inbound());
                } else if (held.outbound() != null) {
                    this.deliver(Direction.SERVERBOUND, held.nanoTime(), Unpooled.wrappedBuffer(held.outbound()));
                }
            }
            this.backlogBytes = 0;
            if (ctx != null) {
                ctx.fireChannelReadComplete();
            }
            if (this.slots.isEmpty()) {
                this.output.notice(whenNone);
            }
        });
    }

    /** Gives up waiting for the engines after {@link #START_TIMEOUT_SECONDS}; call once, on the network thread. */
    public void startTimeout() {
        this.channel.eventLoop().schedule(() -> {
            if (!this.ready && !this.closed) {
                this.output.notice("The engines did not start within " + START_TIMEOUT_SECONDS + " s; SelfDetection is off for this server.");
                this.close("engines too slow to start");
            }
        }, START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------------------------------ network thread

    void clientbound(final ChannelHandlerContext ctx, final ByteBuf buffer) {
        this.inbound = ctx;
        if (this.closed) {
            ctx.fireChannelRead(buffer);
            return;
        }

        if (!this.ready) {
            this.holdInbound(buffer);
            return;
        }
        this.process(ctx, System.nanoTime(), buffer);
    }

    /** A server packet, live: shown to the engines, then given to the client with the engines' pings around it. */
    private void process(final ChannelHandlerContext ctx, final long now, final ByteBuf buffer) {
        List<Integer> pre = new ArrayList<>(0);
        List<Integer> post = new ArrayList<>(0);
        this.before = pre;
        this.after = post;
        try {
            this.deliver(Direction.CLIENTBOUND, now, buffer);
        } finally {
            this.before = null;
            this.after = null;
        }

        for (int id : pre) {
            this.firePing(ctx, id);
        }
        ctx.fireChannelRead(buffer);
        for (int id : post) {
            this.firePing(ctx, id);
        }
        this.markHandled(ctx);
    }

    /**
     * While recording, a marker ping behind every server packet: where the client answers it is where it finished
     * handling that packet, which a replay needs to answer an engine's own transactions at the right place.
     */
    private void markHandled(final ChannelHandlerContext ctx) {
        if (this.recorder == null || this.recorderFailed) {
            return;
        }
        int id = this.pool.allocate(this.markers);
        if (id < 0) {
            return;
        }
        this.record(Recording.Kind.MARK_SENT, id);
        ctx.fireChannelRead(new PingMarker(id));
    }

    void serverbound(final ByteBuf buffer) {
        if (this.closed) {
            return;
        }
        long now = System.nanoTime();
        if (!this.ready) {
            this.holdOutbound(now, buffer);
            return;
        }
        this.deliver(Direction.SERVERBOUND, now, buffer);
    }

    void inboundBatchEnd(final ChannelHandlerContext ctx) {
        this.inbound = ctx;
        if (this.closed || !this.ready) {
            return;
        }
        this.record(Recording.Kind.BATCH_END, 0);
        for (Slot slot : this.slots) {
            if (slot.active()) {
                slot.guard(() -> slot.engine().onInboundBatchEnd());
            }
        }
    }

    void transactionAnswered(final int id) {
        Slot slot = this.pool.complete(id);
        if (this.closed || slot == null) {
            return;
        }
        if (slot == this.markers) {
            this.record(Recording.Kind.MARK_ACK, id);
            return;
        }
        this.record(Recording.Kind.TX_ACK, id);
        if (slot.active()) {
            slot.guard(() -> slot.engine().onTransactionAck(id));
        }
    }

    void transactionDropped(final int id) {
        Slot slot = this.pool.complete(id);
        if (this.closed || slot == null) {
            return;
        }
        if (slot == this.markers) {
            this.record(Recording.Kind.MARK_DROP, id);
            return;
        }
        this.record(Recording.Kind.TX_DROP, id);
        if (slot.active()) {
            slot.guard(() -> slot.engine().onTransactionDropped(id));
        }
    }

    private void deliver(final Direction direction, final long nanoTime, final ByteBuf buffer) {
        if (this.recorder != null && !this.recorderFailed) {
            try {
                this.recorder.packet(direction, nanoTime, buffer);
            } catch (IOException e) {
                this.recordingFailed(e);
            }
        }
        for (Slot slot : this.slots) {
            if (!slot.active()) {
                continue;
            }
            WirePacket packet = new WirePacket(direction, nanoTime, buffer.asReadOnly());
            if (direction == Direction.CLIENTBOUND) {
                slot.guard(() -> slot.engine().onClientbound(packet));
            } else {
                slot.guard(() -> slot.engine().onServerbound(packet));
            }
        }
    }

    /** Keeps a server packet from the client until the engines are ready, or lets everything through if it is too much. */
    private void holdInbound(final ByteBuf buffer) {
        this.backlog.add(new Held(Direction.CLIENTBOUND, System.nanoTime(), buffer, null));
        this.backlogBytes += buffer.readableBytes();
        if (this.backlogBytes > BACKLOG_LIMIT) {
            this.output.notice("Too much arrived before the engines were ready; SelfDetection is off for this server.");
            this.close("backlog overflow");
        }
    }

    /** A copy of a packet the client sent before the engines were ready; the packet itself goes to the server as usual. */
    private void holdOutbound(final long nanoTime, final ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        this.backlog.add(new Held(Direction.SERVERBOUND, nanoTime, null, bytes));
        this.backlogBytes += bytes.length;
    }

    /** Gives the client every server packet still held back, in order (or releases them if the connection is gone). */
    private void letThrough() {
        ChannelHandlerContext ctx = this.inbound;
        boolean open = this.channel.isActive() && ctx != null;
        Held held;
        while ((held = this.backlog.poll()) != null) {
            ByteBuf packet = held.inbound();
            if (packet == null) {
                continue;
            }
            if (open) {
                ctx.fireChannelRead(packet);
            } else {
                packet.release();
            }
        }
        this.backlogBytes = 0;
        if (open) {
            ctx.fireChannelReadComplete();
        }
    }

    private int sendTransaction(final Slot owner, final Placement placement) {
        if (this.closed) {
            return -1;
        }
        int id = this.pool.allocate(owner);
        if (id < 0) {
            return -1;
        }
        if (!this.channel.eventLoop().inEventLoop()) {
            this.channel.eventLoop().execute(() -> this.injectNow(id));
            return id;
        }

        List<Integer> queue = placement == Placement.BEFORE_CURRENT ? this.before : this.after;
        if (queue != null) {
            queue.add(id);
        } else {
            this.injectNow(id);
        }
        return id;
    }

    private void injectNow(final int id) {
        ChannelHandlerContext ctx = this.inbound;
        if (ctx == null || this.closed) {
            this.transactionDropped(id);
            return;
        }
        this.firePing(ctx, id);
    }

    private void firePing(final ChannelHandlerContext ctx, final int id) {
        this.record(Recording.Kind.TX_SENT, id);
        ctx.fireChannelRead(new PingMarker(id));
    }

    private void record(final Recording.Kind kind, final int id) {
        if (this.recorder == null || this.recorderFailed) {
            return;
        }
        try {
            if (kind == Recording.Kind.BATCH_END) {
                this.recorder.batchEnd(System.nanoTime());
            } else {
                this.recorder.transaction(kind, System.nanoTime(), id);
            }
        } catch (IOException e) {
            this.recordingFailed(e);
        }
    }

    private void recordingFailed(final IOException e) {
        this.recorderFailed = true;
        LOGGER.warn("[Sigma/SelfCheck] recording stopped", e);
        this.output.notice("Recording stopped: " + e.getMessage());
    }

    // ------------------------------------------------------------------------------------------ any thread

    /**
     * Routes a typed command to the engine that registered {@code root}. Returns false if no engine did. The engine
     * is called on the network thread.
     */
    public boolean command(final String root, final String arguments) {
        Slot slot = this.commandRoots.get(root.toLowerCase(Locale.ROOT));
        if (slot == null || this.closed) {
            return false;
        }
        this.channel.eventLoop().execute(() -> {
            if (slot.active()) {
                slot.guard(() -> slot.engine().onCommand(root, arguments));
            }
        });
        return true;
    }

    /**
     * The client declined to answer one of our pings (ViaFabricPlus does that on old versions when the player or the
     * window it names does not exist). Called from the game thread.
     */
    public void pingRefused(final int id) {
        this.onNetworkThread(() -> this.transactionDropped(id));
    }

    /**
     * Whether a ping may be delivered while the client's decoder is in the configuration phase. Before 1.20.2 there is
     * no configuration phase on the wire: ViaVersion plays one to the client while the server is already sending play
     * packets, which it holds back - a ping delivered then would overtake them. So for those servers pings wait for
     * the play phase.
     */
    boolean pingsAllowedInConfiguration() {
        return this.target.protocol() >= PROTOCOL_1_20_2;
    }

    /**
     * Runs {@code task} on the network thread, or right here if that thread is already gone - which is when the
     * clean-up matters most.
     */
    private void onNetworkThread(final Runnable task) {
        if (this.channel.eventLoop().inEventLoop()) {
            task.run();
            return;
        }
        try {
            this.channel.eventLoop().execute(task);
        } catch (RejectedExecutionException shuttingDown) {
            task.run();
        }
    }

    /** Stops judging this connection. The handlers stay in the pipeline and pass everything through. */
    public void close(final String reason) {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.onNetworkThread(() -> {
            this.letThrough();
            this.slots.forEach(Slot::shutDown);
            this.slots.clear();
            if (this.recorder != null) {
                try {
                    this.recorder.close();
                } catch (IOException ignored) {
                    // the file is as complete as it is going to get
                }
            }
            this.root.log("[SelfCheck] session for " + this.target.address() + " ended (" + reason + "); "
                    + this.pool.outstandingCount() + " transactions were still unanswered");
        });
    }

    /** One engine's view of the session: the {@link EngineContext} it was created with. */
    public final class Slot implements EngineContext {

        private final String id;
        private final Path dataFolder;
        private final AutoCloseable loader;
        private final Verdicts verdicts;
        private @Nullable SelfCheckEngine engine;
        private double failureScore;
        private boolean switchedOff;

        private Slot(final String id, final Path dataFolder, final AutoCloseable loader) {
            this.id = id;
            this.dataFolder = dataFolder;
            this.loader = loader;
            this.verdicts = new SlotVerdicts(this);
        }

        public String id() {
            return this.id;
        }

        public void attach(final SelfCheckEngine engine) {
            this.engine = engine;
        }

        boolean active() {
            return this.engine != null && !this.switchedOff;
        }

        SelfCheckEngine engine() {
            SelfCheckEngine current = this.engine;
            if (current == null) {
                throw new IllegalStateException("slot " + this.id + " has no engine");
            }
            return current;
        }

        void guard(final Runnable call) {
            try {
                call.run();
                this.failureScore = Math.max(0.0, this.failureScore - FAILURE_DECAY);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError error) {
                    throw error;
                }
                boolean firstInAWhile = this.failureScore == 0.0;
                this.failureScore += 1.0;
                if (firstInAWhile) {
                    LOGGER.warn("[Sigma/SelfCheck] engine {} failed", this.id, t);
                    SelfCheckSession.this.root.log("[" + this.id + "] failed: " + t);
                }
                if (this.failureScore >= FAILURE_LIMIT && !this.switchedOff) {
                    this.switchedOff = true;
                    SelfCheckSession.this.output.notice(this.id + " kept failing and was switched off for this server: " + t);
                    this.shutDown();
                }
            }
        }

        void shutDown() {
            SelfCheckEngine current = this.engine;
            this.switchedOff = true;
            if (current != null) {
                try {
                    current.close();
                } catch (Throwable t) {
                    LOGGER.warn("[Sigma/SelfCheck] engine {} failed to close", this.id, t);
                }
            }
            SelfCheckSession.this.commandRoots.values().removeIf(slot -> slot == this);
            try {
                this.loader.close();
            } catch (Exception ignored) {
                // the loader goes with the garbage collector
            }
        }

        @Override
        public int protocolVersion() {
            return SelfCheckSession.this.target.protocol();
        }

        @Override
        public String protocolName() {
            return SelfCheckSession.this.target.protocolName();
        }

        @Override
        public UUID playerId() {
            return SelfCheckSession.this.target.playerId();
        }

        @Override
        public String playerName() {
            return SelfCheckSession.this.target.playerName();
        }

        @Override
        public String serverAddress() {
            return SelfCheckSession.this.target.address();
        }

        @Override
        public Path dataFolder() {
            return this.dataFolder;
        }

        @Override
        public void log(final String line) {
            SelfCheckSession.this.root.log("[" + this.id + "] " + line);
        }

        @Override
        public Verdicts verdicts() {
            return this.verdicts;
        }

        @Override
        public int sendTransaction(final Placement placement) {
            return SelfCheckSession.this.sendTransaction(this, placement);
        }

        @Override
        public void registerCommand(final String root) {
            SelfCheckSession.this.commandRoots.put(root.toLowerCase(Locale.ROOT), this);
        }

        @Override
        public String commandPrefix() {
            return SelfCheckSession.this.commandPrefix;
        }

        @Override
        public void execute(final Runnable task) {
            SelfCheckSession.this.channel.eventLoop().execute(() -> {
                if (this.active()) {
                    this.guard(task);
                }
            });
        }

        @Override
        public EventLoop eventLoop() {
            return SelfCheckSession.this.channel.eventLoop();
        }
    }

    /** Logs every verdict to the console and passes it to the output, labelled with the engine. */
    private final class SlotVerdicts implements Verdicts {

        private final Slot slot;

        private SlotVerdicts(final Slot slot) {
            this.slot = slot;
        }

        @Override
        public void flag(final String check, final double violations, final String verbose) {
            this.slot.log(String.format(Locale.ROOT, "%s failed %s (x%.1f) %s", this.slot.playerName(), check, violations, verbose));
            SelfCheckSession.this.output.flag(this.slot.id, check, violations, verbose);
        }

        @Override
        public void message(final String componentJson) {
            SelfCheckSession.this.output.message(this.slot.id, componentJson);
        }

        @Override
        public void punishment(final String command) {
            this.slot.log("would run: " + command);
            SelfCheckSession.this.output.punishment(this.slot.id, command);
        }

        @Override
        public void setback(final String reason) {
            this.slot.log("would set back: " + reason);
            SelfCheckSession.this.output.setback(this.slot.id, reason);
        }

        @Override
        public void disconnect(final String reason) {
            this.slot.log("would disconnect: " + reason);
            SelfCheckSession.this.output.disconnect(this.slot.id, reason);
        }
    }
}
