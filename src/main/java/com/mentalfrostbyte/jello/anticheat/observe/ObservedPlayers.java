package com.mentalfrostbyte.jello.anticheat.observe;

import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.check.Detector;
import com.mentalfrostbyte.jello.anticheat.check.ViolationBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The players being watched, and the one place a position report becomes a {@link Sample}.
 *
 * <p>This class is plain Java: everything about the game arrives as arguments and as the {@link WorldProbe},
 * so the whole path from report to alert runs in a test. The packet hooks in {@code ClientPacketListener}
 * feed it from the game thread.</p>
 *
 * <p>It also owns which moments are unjudgeable. A teleport, a velocity push, a block changing near the
 * player and the first two seconds after they appear all make the next few reports meaningless (the world
 * the client sees is not the one the player moved through, or they were moved by something other than their
 * own input), so a report inside one of those windows is marked exempt.</p>
 */
public final class ObservedPlayers {

    private static final long TICK = 50_000_000L;
    private static final long TELEPORT_WINDOW = 4 * TICK;
    private static final long IMPULSE_WINDOW = 20 * TICK;
    private static final long BLOCK_CHANGE_WINDOW = 10 * TICK;
    private static final long SPAWN_WINDOW = 40 * TICK;
    /** A step between two reports longer than this is a teleport, whether or not a teleport packet said so. */
    private static final double SILENT_TELEPORT = 8.0;
    /** Blocks around a block change within which a player's ground and collisions are in doubt. */
    private static final double BLOCK_CHANGE_REACH = 4.0;
    /** The sideways slack when there is no previous report to measure the step from. */
    private static final double UNKNOWN_STEP = 0.5;
    private static final double MAX_STEP_SLACK = 1.0;

    private final Map<Integer, TrackedPlayer> players = new HashMap<>();
    private final Detector detector;
    private final WorldProbe probe;

    public ObservedPlayers(final Detector detector, final WorldProbe probe) {
        this.detector = detector;
        this.probe = probe;
    }

    /** The rest of what the client knows about a player when a report arrives. */
    public record Snapshot(int flags, double moveSpeed, double jumpStrength, double gravity, boolean unknownEffects) {
    }

    public TrackedPlayer track(final int entityId, final UUID uuid, final String name, final long nanos) {
        TrackedPlayer existing = this.players.get(entityId);
        if (existing != null && existing.uuid().equals(uuid)) {
            existing.rename(name);
            return existing;
        }

        TrackedPlayer created = new TrackedPlayer(entityId, uuid, name, nanos);
        this.players.put(entityId, created);
        return created;
    }

    public void untrack(final int entityId) {
        this.players.remove(entityId);
    }

    public TrackedPlayer get(final int entityId) {
        return this.players.get(entityId);
    }

    public Collection<TrackedPlayer> all() {
        return this.players.values();
    }

    public void clear() {
        this.players.clear();
    }

    /** Zeroes every violation level of the player with this UUID, if they are being tracked. */
    public void clearLevels(final UUID uuid) {
        for (TrackedPlayer player : this.players.values()) {
            if (player.uuid().equals(uuid)) {
                player.buffers().values().forEach(ViolationBuffer::clear);
            }
        }
    }

    /** Zeroes every violation level of everyone tracked. */
    public void clearLevels() {
        for (TrackedPlayer player : this.players.values()) {
            player.buffers().values().forEach(ViolationBuffer::clear);
        }
    }

    public void onTeleport(final int entityId, final long nanos) {
        TrackedPlayer player = this.players.get(entityId);
        if (player != null) {
            player.noteTeleport(nanos);
        }
    }

    public void onImpulse(final int entityId, final long nanos) {
        TrackedPlayer player = this.players.get(entityId);
        if (player != null) {
            player.noteImpulse(nanos);
        }
    }

    /** Blocks changed inside the box {@code [min, max]}; players near it stop being judged for a moment. */
    public void onBlocksChanged(final double minX, final double minY, final double minZ,
                                final double maxX, final double maxY, final double maxZ, final long nanos) {
        for (TrackedPlayer player : this.players.values()) {
            Sample last = player.lastSample();
            if (last == null) {
                continue;
            }

            if (last.x() >= minX - BLOCK_CHANGE_REACH && last.x() <= maxX + BLOCK_CHANGE_REACH
                    && last.y() >= minY - BLOCK_CHANGE_REACH && last.y() <= maxY + BLOCK_CHANGE_REACH
                    && last.z() >= minZ - BLOCK_CHANGE_REACH && last.z() <= maxZ + BLOCK_CHANGE_REACH) {
                player.noteBlockChange(nanos);
            }
        }
    }

    /**
     * A position report for a tracked player. Builds the sample, files it, and runs the checks on it.
     *
     * @return the sample, or null if the player is not being tracked
     */
    public Sample onPosition(final int entityId, final double x, final double y, final double z, final boolean onGround,
                             final Snapshot snapshot, final long nanos, final CheckSettings settings) {
        TrackedPlayer player = this.players.get(entityId);
        if (player == null) {
            return null;
        }

        Sample previous = player.lastSample();
        if (previous != null && Math.sqrt(square(x - previous.x()) + square(y - previous.y()) + square(z - previous.z())) > SILENT_TELEPORT) {
            // The server sends a full position report instead of a relative one when the step no longer fits
            // (more than eight blocks), and a legal player cannot move that far between two reports. It is how
            // an ender pearl or a plugin's teleport reaches an observer: as an ordinary position report.
            player.noteTeleport(nanos);
        }

        double step = previous == null ? UNKNOWN_STEP : Math.min(MAX_STEP_SLACK, Math.hypot(x - previous.x(), z - previous.z()));
        double quant = settings.quantStep();
        double horizontalSlack = step + 2.0 * quant;
        double verticalSlack = 2.0 * quant + 0.005;

        boolean loaded = this.probe.chunkLoaded(x, z);
        boolean exempt = withinWindow(nanos, player.lastTeleportNanos(), TELEPORT_WINDOW)
                || withinWindow(nanos, player.lastImpulseNanos(), IMPULSE_WINDOW)
                || withinWindow(nanos, player.lastBlockChangeNanos(), BLOCK_CHANGE_WINDOW)
                || nanos - player.firstSeenNanos() < SPAWN_WINDOW;

        boolean supported = false;
        boolean unjudged = false;
        double friction = Sample.DEFAULT_FRICTION;
        if (loaded) {
            supported = this.probe.supported(x, y, z, horizontalSlack, verticalSlack);
            unjudged = this.probe.unjudgedEnvironment(x, y, z);
            friction = this.probe.blockFriction(x, y, z);
        }

        Sample sample = new Sample(nanos, x, y, z, onGround, snapshot.flags(), snapshot.moveSpeed(),
                snapshot.jumpStrength(), snapshot.gravity(), snapshot.unknownEffects(), friction, supported,
                unjudged, loaded, exempt);
        player.addSample(sample);
        this.detector.ingest(player, sample, settings);
        return sample;
    }

    private static double square(final double value) {
        return value * value;
    }

    private static boolean withinWindow(final long now, final long event, final long window) {
        return event != Long.MIN_VALUE && now - event < window;
    }
}
