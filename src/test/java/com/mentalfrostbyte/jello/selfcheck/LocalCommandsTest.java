package com.mentalfrostbyte.jello.selfcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.selfcheck.host.LocalCommands;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckPipeline;
import com.mentalfrostbyte.jello.selfcheck.host.SelfCheckSession;
import com.mentalfrostbyte.jello.selfcheck.host.ServerRoot;
import com.mentalfrostbyte.jello.selfcheck.host.Target;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.ConnectionProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCommandsTest {

    @TempDir
    Path tmp;

    private final List<String> replies = new ArrayList<>();

    @Test
    void ordinaryChatIsLeftAlone() {
        assertFalse(LocalCommands.handle("hello", ".", null, this.replies::add));
        assertFalse(LocalCommands.handle(".lol", ".", null, this.replies::add), "an unknown root is chat, not a command");
        assertFalse(LocalCommands.handle(". grim", ".", null, this.replies::add));
        assertFalse(LocalCommands.handle("...", ".", null, this.replies::add));
        assertFalse(LocalCommands.handle("/grim alerts", ".", null, this.replies::add), "the server's own /grim is untouched");
        assertTrue(this.replies.isEmpty());
    }

    @Test
    void grimIsAnsweredLocallyEvenWithNoSession() {
        assertTrue(LocalCommands.handle(".grim alerts", ".", null, this.replies::add));
        assertTrue(LocalCommands.handle(".GRIM", ".", null, this.replies::add));
        assertEquals(2, this.replies.size());
        assertTrue(this.replies.get(0).contains("not running"));
    }

    @Test
    void aRegisteredRootGoesToItsEngineWithItsArguments() {
        TestWire wire = new TestWire(ConnectionProtocol.PLAY);
        SelfCheckSession session = new SelfCheckSession(wire.channel,
                new Target(47, "1.8.x", "host", new UUID(0, 1), "Steve"), new ServerRoot(this.tmp), new SelfCheckSessionTest.Output(), null);
        SelfCheckPipeline.install(wire.channel.pipeline(), session);
        SelfCheckSession.Slot slot = session.newSlot("Custom", this.tmp, () -> {
        });
        LoggingEngine engine = new LoggingEngine(slot);
        slot.attach(engine);
        slot.registerCommand("custom");
        session.enginesReady(List.of(slot));
        wire.channel.runPendingTasks();

        assertTrue(LocalCommands.handle(".Custom  verbose   on ", ".", session, this.replies::add));
        wire.channel.runPendingTasks();

        assertEquals(List.of("cmd custom verbose   on"), engine.events);
        assertTrue(this.replies.isEmpty());
        assertTrue(LocalCommands.handle(".grim", ".", session, this.replies::add), "grim is still taken, with an explanation");
        assertEquals(1, this.replies.size());
    }

    @Test
    void aLongerPrefixWorksToo() {
        assertTrue(LocalCommands.handle("!!grim alerts", "!!", null, this.replies::add));
        assertFalse(LocalCommands.handle("!grim alerts", "!!", null, this.replies::add));
        assertFalse(LocalCommands.handle(".grim", "", null, this.replies::add), "an empty prefix takes nothing");
    }
}
