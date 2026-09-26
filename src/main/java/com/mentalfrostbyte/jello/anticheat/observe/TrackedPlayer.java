package com.mentalfrostbyte.jello.anticheat.observe;

import com.mentalfrostbyte.jello.anticheat.check.ViolationBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Everything the detector remembers about one observed player: their recent samples, the events around them
 * that make movement unjudgeable for a moment, and the state each check keeps between samples.
 *
 * <p>Only the game thread touches this, so nothing is synchronised.</p>
 */
public final class TrackedPlayer {

    /** About forty seconds at one sample per second tick. */
    private static final int MAX_SAMPLES = 400;

    private final int entityId;
    private final UUID uuid;
    private String name;

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final Map<String, ViolationBuffer> buffers = new HashMap<>();
    private final Map<String, Object> checkState = new HashMap<>();

    private final long firstSeenNanos;
    private long lastTeleportNanos = Long.MIN_VALUE;
    private long lastImpulseNanos = Long.MIN_VALUE;
    private long lastBlockChangeNanos = Long.MIN_VALUE;

    public TrackedPlayer(final int entityId, final UUID uuid, final String name, final long firstSeenNanos) {
        this.entityId = entityId;
        this.uuid = uuid;
        this.name = name;
        this.firstSeenNanos = firstSeenNanos;
    }

    public int entityId() {
        return this.entityId;
    }

    public UUID uuid() {
        return this.uuid;
    }

    public String name() {
        return this.name;
    }

    public void rename(final String name) {
        this.name = name;
    }

    public long firstSeenNanos() {
        return this.firstSeenNanos;
    }

    public void addSample(final Sample sample) {
        this.samples.addLast(sample);
        while (this.samples.size() > MAX_SAMPLES) {
            this.samples.removeFirst();
        }
    }

    public Sample lastSample() {
        return this.samples.peekLast();
    }

    public int sampleCount() {
        return this.samples.size();
    }

    /** Samples oldest first. */
    public Iterator<Sample> samples() {
        return this.samples.iterator();
    }

    public void noteTeleport(final long nanos) {
        this.lastTeleportNanos = nanos;
    }

    public void noteImpulse(final long nanos) {
        this.lastImpulseNanos = nanos;
    }

    public void noteBlockChange(final long nanos) {
        this.lastBlockChangeNanos = nanos;
    }

    public long lastTeleportNanos() {
        return this.lastTeleportNanos;
    }

    public long lastImpulseNanos() {
        return this.lastImpulseNanos;
    }

    public long lastBlockChangeNanos() {
        return this.lastBlockChangeNanos;
    }

    /** This player's violation level for a check, created on first use. */
    public ViolationBuffer buffer(final String check) {
        return this.buffers.computeIfAbsent(check, key -> new ViolationBuffer());
    }

    public Map<String, ViolationBuffer> buffers() {
        return this.buffers;
    }

    /** A check's private per-player state, created on first use. */
    @SuppressWarnings("unchecked")
    public <T> T state(final String key, final Supplier<T> factory) {
        return (T) this.checkState.computeIfAbsent(key, k -> factory.get());
    }
}
