package com.mentalfrostbyte.jello.selfcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mentalfrostbyte.jello.selfcheck.api.Direction;
import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import com.mentalfrostbyte.jello.selfcheck.api.Placement;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngine;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngineFactory;
import com.mentalfrostbyte.jello.selfcheck.api.Verdicts;
import com.mentalfrostbyte.jello.selfcheck.host.Recording;
import com.mentalfrostbyte.jello.selfcheck.host.Replay;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckOutput;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckPing;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckPipeline;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckPong;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckSession;
import com.mentalfrostbyte.jello.selfcheck.host.ServerRoot;
import com.mentalfrostbyte.jello.selfcheck.host.Target;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.ConnectionProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingReplayTest {

    @TempDir
    Path tmp;

    private static final Verdicts SILENT = new Verdicts() {
        @Override
        public void flag(final String check, final double violations, final String verbose) {
        }

        @Override
        public void message(final String componentJson) {
        }

        @Override
        public void punishment(final String command) {
        }

        @Override
        public void setback(final String reason) {
        }

        @Override
        public void disconnect(final String reason) {
        }
    };

    /** A factory for a {@link LoggingEngine} that sends a transaction behind packet 2 and one in front of packet 3. */
    private static final class Factory implements SelfCheckEngineFactory {
        final List<LoggingEngine> made = new ArrayList<>();

        @Override
        public String id() {
            return "Logging";
        }

        @Override
        public boolean supports(final int protocolVersion) {
            return true;
        }

        @Override
        public SelfCheckEngine create(final EngineContext context) {
            LoggingEngine engine = new LoggingEngine(context);
            engine.transactionsAround.put(2, Placement.AFTER_CURRENT);
            engine.transactionsAround.put(3, Placement.BEFORE_CURRENT);
            this.made.add(engine);
            return engine;
        }
    }

    @Test
    void aRecordingReadsBackEventForEvent() throws IOException {
        Path file = this.tmp.resolve("r.sgsc");
        try (Recording.Writer writer = new Recording.Writer(file, 47, "1.8.x")) {
            writer.packet(Direction.CLIENTBOUND, 10L, Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
            writer.batchEnd(11L);
            writer.transaction(Recording.Kind.TX_SENT, 12L, 0x6000);
            writer.packet(Direction.SERVERBOUND, 13L, new byte[]{9});
            writer.transaction(Recording.Kind.TX_ACK, 14L, 0x6000);
        }

        try (Recording.Reader reader = new Recording.Reader(file)) {
            assertEquals(new Recording.Header(Recording.FORMAT, 47, "1.8.x"), reader.header());
            List<String> events = new ArrayList<>();
            Recording.Event event;
            while ((event = reader.next()) != null) {
                events.add(event.kind() + "@" + event.nanoTime() + (event.bytes() != null ? "#" + event.bytes().length : "/" + event.id()));
            }
            assertEquals(List.of("CLIENTBOUND@10#3", "BATCH_END@11/0", "TX_SENT@12/24576", "SERVERBOUND@13#1", "TX_ACK@14/24576"), events);
        }
    }

    @Test
    void aRecordingCutOffMidEventEndsCleanly() throws IOException {
        Path file = this.tmp.resolve("cut.sgsc");
        try (Recording.Writer writer = new Recording.Writer(file, 47, "1.8.x")) {
            writer.packet(Direction.CLIENTBOUND, 1L, new byte[]{1});
            writer.packet(Direction.CLIENTBOUND, 2L, new byte[]{2, 2, 2, 2});
        }
        byte[] whole = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(whole, whole.length - 2));

        try (Recording.Reader reader = new Recording.Reader(file)) {
            assertEquals(Recording.Kind.CLIENTBOUND, reader.next().kind());
            assertEquals(null, reader.next());
        }
    }

    @Test
    void aLiveSessionReplaysToTheSameEventsWithTheEnginesOwnTransactions() throws Exception {
        // live: record a session in which the engine sends a transaction behind packet 2 and the client answers it
        Path file = this.tmp.resolve("live.sgsc");
        TestWire wire = new TestWire(ConnectionProtocol.PLAY);
        SelfCheckOutput output = new SelfCheckSessionTest.Output();
        SelfCheckSession session = new SelfCheckSession(wire.channel, new Target(47, "1.8.x", "host", new UUID(0, 1), "Steve"),
                new ServerRoot(this.tmp), output, new Recording.Writer(file, 47, "1.8.x"));
        SelfCheckPipeline.install(wire.channel.pipeline(), session);
        SelfCheckSession.Slot slot = session.newSlot("Logging", this.tmp, () -> {
        });
        LoggingEngine live = new LoggingEngine(slot);
        live.transactionsAround.put(2, Placement.AFTER_CURRENT);
        live.transactionsAround.put(3, Placement.BEFORE_CURRENT);
        slot.attach(live);
        session.enginesReady(List.of(slot));
        wire.channel.runPendingTasks();

        wire.receive(1, 2, 3);
        // the client: a movement, then the answer to every ping it was sent (the session's markers and the engine's
        // transaction), in the order it received them, then another movement
        wire.channel.writeOutbound(new TestWire.TestPacket(7));
        for (Object received : wire.packetHandler.received) {
            if (received instanceof SelfCheckPing ping) {
                wire.channel.writeOutbound(new SelfCheckPong(ping.getId()));
            }
        }
        wire.channel.writeOutbound(new TestWire.TestPacket(8));
        session.close("done");
        wire.channel.runPendingTasks();

        // replay: a fresh engine, no network
        Factory factory = new Factory();
        Replay.Result result = Replay.run(file, factory, this.tmp, SILENT, line -> {
        });

        LoggingEngine replayed = factory.made.get(0);
        assertEquals(List.of("S1", "S2", "S3", "end", "C7", "ack", "ack", "C8"), withoutIds(live.events));
        assertEquals(withoutIds(live.events), withoutIds(replayed.events),
                "the replayed engine's transactions are answered where the client answered them live");
        assertEquals(new Replay.Result(3, 2, 2, 2, 0), result);
    }

    private static List<String> withoutIds(final List<String> events) {
        return events.stream().map(e -> e.replaceAll("^(ack|drop)\\d+$", "$1")).toList();
    }
}
