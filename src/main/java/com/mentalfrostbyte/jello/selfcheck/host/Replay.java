package com.mentalfrostbyte.jello.selfcheck.host;

import com.mentalfrostbyte.jello.selfcheck.api.Direction;
import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import com.mentalfrostbyte.jello.selfcheck.api.Placement;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngine;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngineFactory;
import com.mentalfrostbyte.jello.selfcheck.api.Verdicts;
import com.mentalfrostbyte.jello.selfcheck.api.WirePacket;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Plays a {@link Recording} into an engine with no game and no network, in the order it was recorded.
 *
 * <p>The engine sends transactions of its own while it replays, and they need not be the ones the recording holds -
 * the recording may have been made with a different engine. Where the client would have answered them is read off
 * the recording instead:</p>
 * <ul>
 *     <li>Format 2 recordings have a marker behind every server packet, answered exactly when the client had handled
 *     that packet. A transaction the engine sends in front of packet <i>i</i> is answered where the marker behind
 *     packet <i>i - 1</i> was; one sent behind it, or outside a server packet, where the marker behind the latest
 *     server packet was. This is exact.</li>
 *     <li>Format 1 recordings only have the live session's own transactions. A client handles a batch of server
 *     packets in one go at the start of a tick, so a transaction sent while batch <i>k</i> is delivered is answered
 *     where the recording answered one that was sent at the end of batch <i>k</i> (the diagnostic engine sends one
 *     there). That is a tick's worth coarse: it answers a transaction sent in front of a packet only after the
 *     client's replies to that packet, which is enough to confuse an engine that relies on the order.</li>
 * </ul>
 *
 * <p>Like a live session, every engine call runs on one "network thread" - here a single-threaded event loop - and
 * work the engine hands to it from its own threads runs there too, between recorded events. That part depends on
 * timing, so an engine with background threads (Grim ticks 20 times a second) does not replay bit for bit.</p>
 */
public final class Replay {

    /** What went through. */
    public record Result(int clientbound, int serverbound, int transactionsSent, int transactionsAnswered, int transactionsDropped) {
    }

    private Replay() {
    }

    public static Result run(final Path recording, final SelfCheckEngineFactory factory, final Path dataFolder,
                             final Verdicts verdicts, final Consumer<String> log) throws Exception {
        return run(recording, factory, dataFolder, verdicts, log, false);
    }

    /**
     * @param realTime deliver every event when it happened, relative to the first, instead of as fast as possible. An
     *                 engine that measures time itself (Grim's timer checks read the clock) only makes sense this way;
     *                 the replay then takes as long as the recording.
     */
    public static Result run(final Path recording, final SelfCheckEngineFactory factory, final Path dataFolder,
                             final Verdicts verdicts, final Consumer<String> log, final boolean realTime) throws Exception {
        DefaultEventLoop network = new DefaultEventLoop();
        try (Recording.Reader reader = new Recording.Reader(recording)) {
            Context context = new Context(reader.header(), dataFolder, verdicts, log, network);
            if (!factory.supports(reader.header().protocol())) {
                throw new IllegalArgumentException(factory.id() + " does not support protocol " + reader.header().protocol());
            }
            SelfCheckEngine engine = factory.create(context);
            int[] counts = new int[4]; // clientbound, serverbound, answered, dropped
            try {
                Recording.Event event;
                long firstRecorded = -1;
                long started = System.nanoTime();
                while ((event = reader.next()) != null) {
                    if (realTime) {
                        if (firstRecorded < 0) {
                            firstRecorded = event.nanoTime();
                        }
                        long due = started + (event.nanoTime() - firstRecorded);
                        long wait = due - System.nanoTime();
                        if (wait > 0) {
                            Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                        }
                    }
                    Recording.Event current = event;
                    onNetworkThread(network, () -> deliver(engine, context, current, counts));
                }
            } finally {
                onNetworkThread(network, engine::close);
            }
            return new Result(counts[0], counts[1], context.sent, counts[2], counts[3]);
        } finally {
            network.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }

    private static void deliver(final SelfCheckEngine engine, final Context context, final Recording.Event event, final int[] counts) {
        boolean afterBatchEnd = context.afterBatchEnd;
        context.afterBatchEnd = event.kind() == Recording.Kind.BATCH_END || (afterBatchEnd && event.kind() == Recording.Kind.TX_SENT);
        switch (event.kind()) {
            case CLIENTBOUND -> {
                counts[0]++;
                context.delivered++;
                context.inClientbound = true;
                try {
                    engine.onClientbound(packet(Direction.CLIENTBOUND, event));
                } finally {
                    context.inClientbound = false;
                }
            }
            case MARK_SENT -> context.markerAfter.put(event.id(), context.delivered);
            case MARK_ACK, MARK_DROP -> {
                Integer handled = context.markerAfter.remove(event.id());
                context.settle(engine, handled == null ? context.delivered : handled, event.kind() == Recording.Kind.MARK_ACK, counts);
            }
            case SERVERBOUND -> {
                counts[1]++;
                engine.onServerbound(packet(Direction.SERVERBOUND, event));
            }
            case BATCH_END -> {
                engine.onInboundBatchEnd(); // transactions sent now belong to the batch that is ending
                context.ends++;
            }
            case TX_SENT -> {
                if (!context.markers) {
                    // sent at a batch end (straight after BATCH_END), it belongs to the batch that ended; else to the open one
                    context.recordedBatches.put(event.id(), afterBatchEnd ? context.ends : context.ends + 1);
                    context.recordingHasTransactions = true;
                }
            }
            case TX_ACK, TX_DROP -> {
                if (!context.markers) {
                    Integer batch = context.recordedBatches.remove(event.id());
                    context.settle(engine, batch == null ? context.ends : batch, event.kind() == Recording.Kind.TX_ACK, counts);
                }
            }
        }
        // Format 1, before the recording's first transaction: answered by the first client packet after its batch.
        if (!context.markers && event.kind() == Recording.Kind.SERVERBOUND && !context.recordingHasTransactions) {
            context.settle(engine, context.ends, true, counts);
        }
    }

    /** Runs {@code task} on the replay's network thread and waits for it, passing on whatever it throws. */
    private static void onNetworkThread(final EventLoop network, final Runnable task) throws Exception {
        try {
            network.submit(task).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static WirePacket packet(final Direction direction, final Recording.Event event) {
        byte[] bytes = event.bytes();
        return new WirePacket(direction, event.nanoTime(), Unpooled.wrappedBuffer(bytes == null ? new byte[0] : bytes).asReadOnly());
    }

    private static final class Context implements EngineContext {

        private final Recording.Header header;
        private final Path dataFolder;
        private final Verdicts verdicts;
        private final Consumer<String> log;
        private final EventLoop network;
        /** The engine's transactions still waiting, with the batch each was sent in, oldest first. */
        private final ArrayDeque<long[]> pending = new ArrayDeque<>();
        private final Map<Integer, Integer> recordedBatches = new HashMap<>();
        private final Map<Integer, Integer> markerAfter = new HashMap<>();
        private final boolean markers;
        private boolean recordingHasTransactions;
        private boolean afterBatchEnd;
        private boolean inClientbound;
        private int ends;
        private int delivered;
        private int next = TransactionPool.FIRST;
        private int sent;

        private Context(final Recording.Header header, final Path dataFolder, final Verdicts verdicts, final Consumer<String> log,
                        final EventLoop network) {
            this.header = header;
            this.dataFolder = dataFolder;
            this.verdicts = verdicts;
            this.log = log;
            this.network = network;
            this.markers = header.hasMarkers();
        }

        @Override
        public int protocolVersion() {
            return this.header.protocol();
        }

        @Override
        public String protocolName() {
            return this.header.protocolName();
        }

        @Override
        public UUID playerId() {
            return new UUID(0L, 0L);
        }

        @Override
        public String playerName() {
            return "Replay";
        }

        @Override
        public String serverAddress() {
            return "replay";
        }

        @Override
        public Path dataFolder() {
            return this.dataFolder;
        }

        @Override
        public void log(final String line) {
            this.log.accept(line);
        }

        @Override
        public Verdicts verdicts() {
            return this.verdicts;
        }

        /** Engines send from the network thread (they must, live); synchronized in case one does not. */
        @Override
        public synchronized int sendTransaction(final Placement placement) {
            int id = this.next;
            this.next = this.next == TransactionPool.LAST ? TransactionPool.FIRST : this.next + 1;
            long answerAt;
            if (this.markers) {
                // answered once the client handled the packet before this transaction
                answerAt = this.inClientbound && placement == Placement.BEFORE_CURRENT ? this.delivered - 1L : this.delivered;
            } else {
                answerAt = this.ends + 1L;
            }
            this.pending.add(new long[]{id, answerAt});
            this.sent++;
            return id;
        }

        /**
         * Answers (or, for a dropped one, gives up on) every waiting transaction due at {@code point} or earlier - a
         * server packet index with markers, a batch number without.
         */
        private void settle(final SelfCheckEngine engine, final int point, final boolean answered, final int[] counts) {
            long[] head;
            while ((head = this.pollUpTo(point)) != null) {
                int id = (int) head[0];
                if (answered) {
                    counts[2]++;
                    engine.onTransactionAck(id);
                } else {
                    counts[3]++;
                    engine.onTransactionDropped(id);
                }
            }
        }

        private synchronized long[] pollUpTo(final int batch) {
            long[] head = this.pending.peek();
            return head != null && head[1] <= batch ? this.pending.poll() : null;
        }

        @Override
        public void registerCommand(final String root) {
        }

        @Override
        public String commandPrefix() {
            return ".";
        }

        @Override
        public void execute(final Runnable task) {
            this.network.execute(task);
        }

        @Override
        public EventLoop eventLoop() {
            return this.network;
        }
    }
}
