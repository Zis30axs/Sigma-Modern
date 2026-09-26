package com.mentalfrostbyte.jello.selfcheck.host;

import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngine;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngineFactory;
import com.mojang.logging.LogUtils;
import io.netty.channel.Channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Starts a {@link SelfCheckSession} for a connection being opened to a server, and remembers the one that is
 * running. A client plays on one server at a time, so there is at most one.
 */
public final class SelfCheck {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile @Nullable SelfCheckSession current;

    /**
     * @param grim        run the built-in Grim
     * @param plugins     run the engines in {@code plugins/*.jar}
     * @param record      write the connection's traffic to {@code recordings/}
     * @param diagnostics run the diagnostic engine that measures the transaction channel
     * @param commandPrefix what local commands start with, see {@link LocalCommands}
     */
    public record Settings(boolean grim, boolean plugins, boolean record, boolean diagnostics, String commandPrefix) {

        List<String> builtIn() {
            List<String> names = new ArrayList<>();
            if (this.grim) {
                names.add(EngineLoader.GRIM);
            }
            if (this.diagnostics) {
                names.add(EngineLoader.DIAGNOSTICS);
            }
            return names;
        }
    }

    private SelfCheck() {
    }

    /**
     * The game thread decided not to answer {@code packet}. If it was one of ours, the engine that sent it is told the
     * transaction was dropped instead of waiting for an answer that will never come.
     */
    public static void refused(final ClientboundPingPacket packet) {
        SelfCheckSession session = current();
        if (packet instanceof SelfCheckPing && session != null) {
            session.pingRefused(packet.getId());
        }
    }

    /** The session judging the current connection, or null if there is none. */
    public static @Nullable SelfCheckSession current() {
        SelfCheckSession session = current;
        return session != null && !session.isClosed() ? session : null;
    }

    /**
     * Puts a session into {@code channel}'s pipeline and starts the engines on a background thread; traffic that
     * arrives before they are ready is held back. Called while the channel is being initialised, on its network
     * thread, after ViaFabricPlus added its handlers.
     */
    public static SelfCheckSession start(final Channel channel, final Target target, final ServerRoot root,
                                         final SelfCheckOutput output, final Settings settings) {
        SelfCheckSession previous = current;
        if (previous != null) {
            previous.close("replaced by a new connection");
        }

        Recording.Writer recorder = null;
        if (settings.record()) {
            try {
                recorder = new Recording.Writer(root.newRecording(target.address()), target.protocol(), target.protocolName());
            } catch (IOException e) {
                output.notice("Could not start recording: " + e.getMessage());
            }
        }

        SelfCheckSession session = new SelfCheckSession(channel, target, root, output, recorder, settings.commandPrefix());
        SelfCheckPipeline.install(channel.pipeline(), session);
        session.startTimeout();
        current = session;
        root.log("=== " + target.playerName() + " connecting to " + target.address() + " (" + target.protocolName() + ") ===");

        Thread loader = new Thread(() -> startEngines(session, root, output, settings), "Sigma SelfCheck engine loader");
        loader.setDaemon(true);
        loader.start();
        return session;
    }

    private static void startEngines(final SelfCheckSession session, final ServerRoot root, final SelfCheckOutput output,
                                     final Settings settings) {
        Consumer<String> problems = problem -> {
            root.log("[SelfCheck] " + problem);
            output.notice(problem);
        };
        ClassLoader parent = SelfCheck.class.getClassLoader();
        List<EngineLoader.Loaded> found = new ArrayList<>();
        List<String> builtIn = settings.builtIn();
        if (!builtIn.isEmpty()) {
            found.addAll(EngineLoader.builtIn(builtIn, parent, problems));
        }
        if (settings.plugins()) {
            try {
                found.addAll(EngineLoader.plugins(root.pluginJars(), parent, problems));
            } catch (IOException e) {
                problems.accept("plugins/ could not be listed: " + e.getMessage());
            }
        }

        // Each engine starts on its own thread: the server's packets wait for the slowest one, not for all of them in a row.
        List<CompletableFuture<SelfCheckSession.@Nullable Slot>> starting = new ArrayList<>();
        for (EngineLoader.Loaded loaded : found) {
            CompletableFuture<SelfCheckSession.@Nullable Slot> future = new CompletableFuture<>();
            Thread thread = new Thread(() -> future.complete(startEngine(session, root, loaded, problems)),
                    "Sigma SelfCheck start " + loaded.origin());
            thread.setDaemon(true);
            thread.start();
            starting.add(future);
        }
        List<SelfCheckSession.Slot> started = new ArrayList<>();
        for (CompletableFuture<SelfCheckSession.@Nullable Slot> future : starting) {
            SelfCheckSession.Slot slot = future.join();
            if (slot != null) {
                started.add(slot);
            }
        }
        if (found.isEmpty() && !settings.grim()) {
            // Nothing was asked to run, which is a setting rather than a failure: say which one.
            session.enginesReady(started, settings.plugins()
                    ? "GrimAC is switched off (SelfDetection > Grim) and plugins/ has no engine; nothing is being checked on this server."
                    : "GrimAC is switched off (SelfDetection > Grim); nothing is being checked on this server.");
        } else {
            session.enginesReady(started);
        }
    }

    /** Creates one engine with its own loader as the context class loader; null if it does not take this connection. */
    private static SelfCheckSession.@Nullable Slot startEngine(final SelfCheckSession session, final ServerRoot root,
                                                               final EngineLoader.Loaded loaded, final Consumer<String> problems) {
        SelfCheckEngineFactory factory = loaded.factory();
        Target target = session.target();
        if (session.isClosed()) {
            loaded.close();
            return null;
        }

        String id;
        try {
            id = factory.id();
            if (!factory.supports(target.protocol())) {
                root.log("[SelfCheck] " + id + " does not support " + target.protocolName() + "; skipped");
                loaded.close();
                return null;
            }
        } catch (Throwable t) {
            problems.accept(loaded.origin() + " failed to describe itself: " + t);
            loaded.close();
            return null;
        }

        Thread thread = Thread.currentThread();
        thread.setContextClassLoader(loaded.loader());
        long began = System.nanoTime();
        try {
            SelfCheckSession.Slot slot = session.newSlot(id, root.pluginFolder(id), loaded);
            SelfCheckEngine engine = factory.create(slot);
            slot.attach(engine);
            root.log(String.format("[SelfCheck] %s (%s) enabled in %d ms", id, loaded.origin(), (System.nanoTime() - began) / 1_000_000));
            return slot;
        } catch (Throwable t) {
            LOGGER.warn("[Sigma/SelfCheck] engine {} failed to start", id, t);
            problems.accept(id + " failed to start: " + t);
            loaded.close();
            return null;
        }
    }
}
