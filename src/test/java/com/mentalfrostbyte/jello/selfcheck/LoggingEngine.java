package com.mentalfrostbyte.jello.selfcheck;

import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import com.mentalfrostbyte.jello.selfcheck.api.Placement;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngine;
import com.mentalfrostbyte.jello.selfcheck.api.WirePacket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * An engine that writes down everything it is shown, as {@code S<n>} / {@code C<n>} for the first byte of a
 * clientbound / serverbound packet, {@code end} for a batch end, {@code ack<id>} / {@code drop<id>} for transactions.
 * It can be told to send transactions around chosen clientbound packets and to throw on chosen ones.
 */
public final class LoggingEngine implements SelfCheckEngine {

    public final List<String> events = new ArrayList<>();
    public final Map<Integer, Placement> transactionsAround = new HashMap<>();
    public final List<Integer> sentIds = new ArrayList<>();
    public IntPredicate throwOn = number -> false;
    public boolean closed;

    private final EngineContext context;

    public LoggingEngine(final EngineContext context) {
        this.context = context;
    }

    @Override
    public void onClientbound(final WirePacket packet) {
        int number = packet.buffer().getUnsignedByte(packet.buffer().readerIndex());
        this.events.add("S" + number);
        if (this.throwOn.test(number)) {
            throw new IllegalStateException("told to fail on " + number);
        }
        Placement placement = this.transactionsAround.get(number);
        if (placement != null) {
            this.sentIds.add(this.context.sendTransaction(placement));
        }
    }

    @Override
    public void onServerbound(final WirePacket packet) {
        this.events.add("C" + packet.buffer().getUnsignedByte(packet.buffer().readerIndex()));
    }

    @Override
    public void onInboundBatchEnd() {
        this.events.add("end");
    }

    @Override
    public void onTransactionAck(final int id) {
        this.events.add("ack" + id);
    }

    @Override
    public void onTransactionDropped(final int id) {
        this.events.add("drop" + id);
    }

    @Override
    public void onCommand(final String root, final String arguments) {
        this.events.add("cmd " + root + " " + arguments);
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
