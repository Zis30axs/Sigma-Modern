package com.mentalfrostbyte.jello.selfcheck.engine.diag;

import com.mentalfrostbyte.jello.selfcheck.api.Direction;
import com.mentalfrostbyte.jello.selfcheck.api.EngineContext;
import com.mentalfrostbyte.jello.selfcheck.api.Placement;
import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngine;
import com.mentalfrostbyte.jello.selfcheck.api.WirePacket;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Judges nothing; measures the channel every real engine depends on. It sends one transaction at the end of every
 * batch the server sends - which is what a server-side anticheat does at the end of every tick - and keeps count of
 * how many were answered, how long that took, and how many were dropped or never answered at all. For a healthy
 * connection sent = answered + dropped + (the few in flight), and nothing stays unanswered.
 *
 * <p>{@code .diag} prints the numbers; they are also logged every ten seconds and when the connection ends.</p>
 */
final class DiagnosticsEngine implements SelfCheckEngine {

    private static final long REPORT_INTERVAL = 10_000_000_000L;

    private final EngineContext context;
    private final Map<Integer, Long> inFlight = new HashMap<>();
    private final long[] packets = new long[2];
    private final long[] bytes = new long[2];
    private long sent;
    private long answered;
    private long dropped;
    private long refused;
    private long roundTripTotal;
    private long roundTripMax;
    private long lastReport = System.nanoTime();

    DiagnosticsEngine(final EngineContext context) {
        this.context = context;
        context.registerCommand("diag");
        context.log("measuring the transaction channel to " + context.serverAddress() + " (" + context.protocolName() + ")");
    }

    @Override
    public void onClientbound(final WirePacket packet) {
        this.count(packet);
    }

    @Override
    public void onServerbound(final WirePacket packet) {
        this.count(packet);
    }

    private void count(final WirePacket packet) {
        int side = packet.direction() == Direction.CLIENTBOUND ? 0 : 1;
        this.packets[side]++;
        this.bytes[side] += packet.buffer().readableBytes();
    }

    @Override
    public void onInboundBatchEnd() {
        int id = this.context.sendTransaction(Placement.NOW);
        if (id < 0) {
            this.refused++;
        } else {
            this.sent++;
            this.inFlight.put(id, System.nanoTime());
        }

        long now = System.nanoTime();
        if (now - this.lastReport >= REPORT_INTERVAL) {
            this.lastReport = now;
            this.context.log(this.summary());
        }
    }

    @Override
    public void onTransactionAck(final int id) {
        Long at = this.inFlight.remove(id);
        if (at == null) {
            return;
        }
        long roundTrip = System.nanoTime() - at;
        this.answered++;
        this.roundTripTotal += roundTrip;
        this.roundTripMax = Math.max(this.roundTripMax, roundTrip);
    }

    @Override
    public void onTransactionDropped(final int id) {
        if (this.inFlight.remove(id) != null) {
            this.dropped++;
        }
    }

    @Override
    public void onCommand(final String root, final String arguments) {
        JsonObject line = new JsonObject();
        line.addProperty("text", "[Diagnostics] " + this.summary());
        line.addProperty("color", "gray");
        this.context.verdicts().message(line.toString());
    }

    @Override
    public void close() {
        this.context.log("final: " + this.summary());
    }

    private String summary() {
        double averageMs = this.answered == 0 ? 0.0 : this.roundTripTotal / (double) this.answered / 1e6;
        return String.format(Locale.ROOT,
                "S %d packets / %d KB, C %d packets / %d KB; transactions sent %d, answered %d, dropped %d, in flight %d, refused %d; round trip avg %.1f ms, max %.1f ms",
                this.packets[0], this.bytes[0] / 1024, this.packets[1], this.bytes[1] / 1024,
                this.sent, this.answered, this.dropped, this.inFlight.size(), this.refused, averageMs, this.roundTripMax / 1e6);
    }
}
