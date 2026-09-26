package com.mentalfrostbyte.jello.selfcheck.api;

import io.netty.channel.EventLoop;
import java.nio.file.Path;
import java.util.UUID;

/** Everything an engine gets from the client for one connection. */
public interface EngineContext {

    /** The protocol number the wire speaks, which is the server's: 47 for 1.8.x, 754 for 1.16.5 and so on. */
    int protocolVersion();

    /** A readable name for {@link #protocolVersion()}, such as {@code 1.8.x}. */
    String protocolName();

    UUID playerId();

    String playerName();

    /** The address the player typed, host and port. */
    String serverAddress();

    /**
     * {@code plugins/<engine id>/} under the self-check root, laid out like a server plugin's data folder. It
     * exists by the time the engine is created.
     */
    Path dataFolder();

    /** Writes a line to {@code logs/latest.log}, the console of this shadow server. */
    void log(String line);

    Verdicts verdicts();

    /**
     * Sends a transaction: a ping the client answers in the order it handles what it was sent. Returns the
     * id; the answer arrives through {@link SelfCheckEngine#onTransactionAck} in the order the client sent it,
     * or {@link SelfCheckEngine#onTransactionDropped} if it could not be delivered. Returns -1 when no id is
     * free, which only happens if the client stops answering altogether.
     */
    int sendTransaction(Placement placement);

    /**
     * Routes chat lines the player types as {@code <prefix><root> ...} (for example {@code .grim alerts}) to
     * {@link SelfCheckEngine#onCommand}. They never reach the server.
     */
    void registerCommand(String root);

    /**
     * What the player types in front of a command root ({@code .} by default). An engine that shows clickable text
     * running one of its commands uses it, so the click stays local instead of sending {@code /root} to the server.
     */
    String commandPrefix();

    /**
     * Runs {@code task} on the connection's network thread, the thread every other callback runs on. Engines
     * that do work elsewhere hop back through here.
     */
    void execute(Runnable task);

    /**
     * The connection's network thread as a netty event loop, for engine code that expects a channel's event loop
     * (a server-side anticheat schedules work on its player's channel). Tasks run on it are not guarded like
     * {@link #execute}: an exception is logged by netty and does not reach the connection either way.
     */
    EventLoop eventLoop();
}
