package com.mentalfrostbyte.jello.selfcheck.api;

/**
 * One anticheat judging the local player for one connection.
 *
 * <p>Every callback runs on the connection's network thread, one at a time, in the order the packets crossed
 * the wire - the same situation a server-side anticheat is in. An engine that throws loses that one call; one
 * that keeps throwing is switched off for the rest of the connection. The real connection never notices.</p>
 */
public interface SelfCheckEngine extends AutoCloseable {

    void onClientbound(WirePacket packet);

    void onServerbound(WirePacket packet);

    /**
     * The end of a batch of packets read from the server. Servers flush once per tick, so this is about where a
     * server tick ends.
     */
    default void onInboundBatchEnd() {
    }

    /** The client answered transaction {@code id}; everything it sent before this, it sent before handling it. */
    default void onTransactionAck(final int id) {
    }

    /** Transaction {@code id} never reached the client (the connection was changing phase) and never will. */
    default void onTransactionDropped(final int id) {
    }

    /** The player typed {@code <prefix><root> <arguments>} for a root this engine registered. */
    default void onCommand(final String root, final String arguments) {
    }

    /** The connection is gone. Stop threads and release files here; the engine's class loader is dropped next. */
    @Override
    default void close() {
    }
}
