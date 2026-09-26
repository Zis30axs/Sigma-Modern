package com.mentalfrostbyte.jello.selfcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.selfcheck.api.Placement;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckOutput;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckPing;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckPipeline;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckPong;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckSession;
import com.mentalfrostbyte.jello.selfcheck.host.ServerRoot;
import com.mentalfrostbyte.jello.selfcheck.host.Target;
import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfCheckSessionTest {

    private static final Target TARGET = new Target(47, "1.8.x", "test.example:25565", new UUID(1, 2), "Steve");

    @TempDir
    Path tmp;

    static final class Output implements SelfCheckOutput {
        final List<String> lines = new ArrayList<>();

        @Override
        public void flag(final String engine, final String check, final double violations, final String verbose) {
            this.lines.add("flag " + check);
        }

        @Override
        public void message(final String engine, final String componentJson) {
            this.lines.add("message " + componentJson);
        }

        @Override
        public void punishment(final String engine, final String command) {
            this.lines.add("punishment " + command);
        }

        @Override
        public void setback(final String engine, final String reason) {
            this.lines.add("setback " + reason);
        }

        @Override
        public void disconnect(final String engine, final String reason) {
            this.lines.add("disconnect " + reason);
        }

        @Override
        public void notice(final String text) {
            this.lines.add("notice " + text);
        }
    }

    private final Output output = new Output();

    private record Fixture(TestWire wire, SelfCheckSession session, LoggingEngine engine) {
    }

    private Fixture fixture(final ConnectionProtocol phase, final boolean ready) {
        TestWire wire = new TestWire(phase);
        SelfCheckSession session = new SelfCheckSession(wire.channel, TARGET, new ServerRoot(this.tmp), this.output, null);
        SelfCheckPipeline.install(wire.channel.pipeline(), session);
        SelfCheckSession.Slot slot = session.newSlot("Test", this.tmp, () -> {
        });
        LoggingEngine engine = new LoggingEngine(slot);
        slot.attach(engine);
        if (ready) {
            session.enginesReady(List.of(slot));
            wire.channel.runPendingTasks();
        }
        return new Fixture(wire, session, engine);
    }

    private static List<String> delivered(final TestWire wire) {
        List<String> names = new ArrayList<>();
        for (Object message : wire.packetHandler.received) {
            if (message instanceof SelfCheckPing ping) {
                names.add("ping" + ping.getId());
            } else if (message instanceof TestWire.TestPacket packet) {
                names.add("P" + packet.number());
            } else {
                names.add(message.toString());
            }
        }
        return names;
    }

    @Test
    void pingsGoAheadOfOrBehindThePacketTheEngineAskedAbout() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        f.engine.transactionsAround.put(2, Placement.BEFORE_CURRENT);
        f.engine.transactionsAround.put(3, Placement.AFTER_CURRENT);

        f.wire.receive(1, 2, 3);

        int first = f.engine.sentIds.get(0);
        int second = f.engine.sentIds.get(1);
        assertEquals(List.of("P1", "ping" + first, "P2", "P3", "ping" + second), delivered(f.wire));
        assertEquals(List.of("S1", "S2", "S3", "end"), f.engine.events);
    }

    @Test
    void answersToOurPingsNeverLeaveAndArriveInSendingOrder() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        f.engine.transactionsAround.put(2, Placement.BEFORE_CURRENT);
        f.engine.transactionsAround.put(3, Placement.AFTER_CURRENT);
        f.wire.receive(1, 2, 3);
        int first = f.engine.sentIds.get(0);
        int second = f.engine.sentIds.get(1);
        f.engine.events.clear();

        f.wire.channel.writeOutbound(new TestWire.TestPacket(7), new SelfCheckPong(first), new TestWire.TestPacket(8), new SelfCheckPong(second));

        assertEquals(List.of("C7", "ack" + first, "C8", "ack" + second), f.engine.events);
        assertEquals(List.of(7, 8), f.wire.sent(), "the answers to our own pings must not reach the server");
    }

    @Test
    void theServersOwnPongPassesEvenWithANumberWeUse() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        f.engine.transactionsAround.put(1, Placement.AFTER_CURRENT);
        f.wire.receive(1);
        int ours = f.engine.sentIds.get(0);
        f.engine.events.clear();

        f.wire.channel.writeOutbound(new ServerboundPongPacket(ours));

        assertEquals(List.of(0xFE), f.wire.sent(), "a plain pong answers the server and must go out");
        assertEquals(List.of("C254"), f.engine.events, "the engine sees it as ordinary traffic, not as its answer");
    }

    @Test
    void anAnswerArrivingAfterTheSessionEndedStillNeverLeaves() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        f.engine.transactionsAround.put(1, Placement.AFTER_CURRENT);
        f.wire.receive(1);
        int ours = f.engine.sentIds.get(0);
        f.session.close("test");
        f.wire.channel.runPendingTasks();

        f.wire.channel.writeOutbound(new SelfCheckPong(ours), new TestWire.TestPacket(9));

        assertEquals(List.of(9), f.wire.sent());
    }

    @Test
    void serverPacketsWaitForTheEnginesAndArriveLiveOnceTheyAreReady() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, false);
        f.wire.receive(1, 2);
        f.wire.channel.writeOutbound(new TestWire.TestPacket(5));
        assertTrue(delivered(f.wire).isEmpty(), "the client does not see what the engines cannot watch yet");
        assertEquals(List.of(5), f.wire.sent(), "the client's own packets go out as usual");
        assertTrue(f.engine.events.isEmpty());

        SelfCheckSession.Slot slot = f.session.newSlot("Late", this.tmp, () -> {
        });
        LoggingEngine late = new LoggingEngine(slot);
        slot.attach(late);
        late.transactionsAround.put(1, Placement.BEFORE_CURRENT);
        f.session.enginesReady(List.of(slot));
        f.wire.channel.runPendingTasks();

        assertEquals(List.of("S1", "S2", "C5"), late.events, "in the order they crossed the taps");
        int id = late.sentIds.get(0);
        assertEquals(List.of("ping" + id, "P1", "P2"), delivered(f.wire),
                "held packets reach the client only now, so a ping can still go in front of one");

        f.wire.receive(3);
        assertEquals(List.of("S1", "S2", "C5", "S3", "end"), late.events);
    }

    @Test
    void givingUpOnTheEnginesLetsEverythingHeldThrough() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, false);
        f.wire.receive(1, 2);
        assertTrue(delivered(f.wire).isEmpty());

        f.session.close("engines too slow");
        f.wire.channel.runPendingTasks();

        assertEquals(List.of("P1", "P2"), delivered(f.wire));
        f.wire.receive(3);
        assertEquals(List.of("P1", "P2", "P3"), delivered(f.wire), "and nothing is held after that");
    }

    @Test
    void noPingIsDeliveredOutsideConfigurationAndPlay() {
        Fixture f = this.fixture(ConnectionProtocol.LOGIN, true);
        f.engine.transactionsAround.put(1, Placement.BEFORE_CURRENT);

        f.wire.receive(1);

        int id = f.engine.sentIds.get(0);
        assertEquals(List.of("P1"), delivered(f.wire));
        assertEquals(List.of("S1", "drop" + id, "end"), f.engine.events);
    }

    @Test
    void noPingIsDeliveredWhileTheDecoderIsBeingSwapped() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        f.wire.channel.pipeline().replace(HandlerNames.DECODER, HandlerNames.INBOUND_CONFIG, new TestWire.PassInbound());
        f.engine.transactionsAround.put(1, Placement.AFTER_CURRENT);

        f.wire.receive(1);

        int id = f.engine.sentIds.get(0);
        assertEquals(List.of("S1", "drop" + id, "end"), f.engine.events);
        assertTrue(f.wire.packetHandler.received.stream().noneMatch(m -> m instanceof SelfCheckPing));
    }

    @Test
    void pingsWaitInLineWhileReadingIsPaused() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        f.engine.transactionsAround.put(2, Placement.BEFORE_CURRENT);
        f.wire.channel.config().setAutoRead(false);

        f.wire.receive(1, 2);
        assertTrue(f.wire.packetHandler.received.isEmpty(), "flow control holds everything while reading is paused");

        for (int i = 0; i < 3; i++) {
            f.wire.channel.read();
        }
        int id = f.engine.sentIds.get(0);
        assertEquals(List.of("P1", "ping" + id, "P2"), delivered(f.wire));
    }

    @Test
    void anEngineThatKeepsFailingIsSwitchedOffAndTheConnectionCarriesOn() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        f.engine.throwOn = number -> true;

        for (int i = 1; i <= 15; i++) {
            f.wire.receive(i);
        }

        assertEquals(15, f.wire.packetHandler.received.size(), "every packet still reached the client");
        assertTrue(f.engine.closed);
        long seen = f.engine.events.stream().filter(e -> e.startsWith("S")).count();
        assertTrue(seen < 15, "the engine is not called again once switched off, saw " + seen);
        assertTrue(this.output.lines.stream().anyMatch(line -> line.startsWith("notice Test kept failing")));
    }

    @Test
    void oneFailureDoesNotCostTheEngineTheNextPacket() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        f.engine.throwOn = number -> number == 1;

        f.wire.receive(1, 2);

        assertEquals(List.of("S1", "S2", "end"), f.engine.events);
        assertFalse(f.engine.closed);
    }

    @Test
    void theTapsAreMovedBackBesideTheViaHandlersAfterViaReordersThem() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        var pipeline = f.wire.channel.pipeline();
        pipeline.addFirst(HandlerNames.DECOMPRESS, new TestWire.PassInbound());
        pipeline.addBefore(SelfCheckPipeline.OUTBOUND_TAP, HandlerNames.COMPRESS, new TestWire.PassOutbound());
        // what ViaChannelInitializer.reorderPipeline does when it moves the Via handlers next to (de)compression
        pipeline.addAfter(HandlerNames.DECOMPRESS, ViaDecodeHandler.NAME, pipeline.remove(ViaDecodeHandler.NAME));
        pipeline.addAfter(HandlerNames.COMPRESS, ViaEncodeHandler.NAME, pipeline.remove(ViaEncodeHandler.NAME));

        SelfCheckPipeline.reanchor(pipeline);

        List<String> names = pipeline.names();
        assertEquals(names.indexOf(SelfCheckPipeline.INBOUND_TAP) + 1, names.indexOf(ViaDecodeHandler.NAME));
        assertEquals(names.indexOf(HandlerNames.DECOMPRESS) + 1, names.indexOf(SelfCheckPipeline.INBOUND_TAP));
        assertEquals(names.indexOf(SelfCheckPipeline.OUTBOUND_TAP) + 1, names.indexOf(ViaEncodeHandler.NAME));
        assertEquals(names.indexOf(HandlerNames.COMPRESS) + 1, names.indexOf(SelfCheckPipeline.OUTBOUND_TAP));

        f.engine.transactionsAround.put(1, Placement.AFTER_CURRENT);
        f.wire.receive(1);
        assertEquals(List.of("P1", "ping" + f.engine.sentIds.get(0)), delivered(f.wire), "still working after the move");
    }

    @Test
    void commandsReachTheEngineThatRegisteredTheRoot() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        SelfCheckSession.Slot slot = f.session.newSlot("Other", this.tmp, () -> {
        });
        slot.registerCommand("Grim");

        assertTrue(f.session.command("grim", "alerts"));
        assertFalse(f.session.command("vulcan", ""));
    }

    @Test
    void aClosedSessionPassesEverythingThroughAndClosesItsEngines() {
        Fixture f = this.fixture(ConnectionProtocol.PLAY, true);
        f.session.close("test");
        f.wire.channel.runPendingTasks();

        f.wire.receive(1);
        f.wire.channel.writeOutbound(new TestWire.TestPacket(2));

        assertTrue(f.engine.closed);
        assertTrue(f.engine.events.isEmpty());
        assertEquals(List.of("P1"), delivered(f.wire));
        assertEquals(List.of(2), f.wire.sent());
    }
}
