package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.combat.AntiBot;
import com.mentalfrostbyte.jello.module.impl.combat.KillAura;
import com.mentalfrostbyte.jello.module.impl.gui.Radar;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Bounded, tick-cached contact snapshots. Nothing here changes a combat target or sends a packet. */
public final class RadarContacts {
    static final int MAX_CONTACTS = 96;
    private static final Frame EMPTY = new Frame(List.of(), -1, 0.0);
    private static final Tracker TRACKER = new Tracker();
    private static WeakReference<Object> world = new WeakReference<>(null);
    private static WeakReference<Object> player = new WeakReference<>(null);
    private static Settings settings;
    private static long sampledTick = Long.MIN_VALUE;
    private static Frame cached = EMPTY;

    private RadarContacts() {}

    /** Position at seenTick; velocity is in blocks per tick. No snapshot retains a live entity. */
    public record Contact(int entityId, String name, Vec3 position, Vec3 velocity, boolean ally, long seenTick) {}

    public record Frame(List<Contact> contacts, int selectedId, double sweepDegrees, boolean tracking) {
        public Frame { contacts = List.copyOf(contacts); }

        public Frame(List<Contact> contacts, int selectedId, double sweepDegrees) {
            this(contacts, selectedId, sweepDegrees, false);
        }
    }

    private record Settings(Radar.Mode mode, boolean headOn, float range, float angle,
                            boolean players, boolean mobs, boolean invisibles, boolean teams,
                            int periodTicks, long session) {
        static Settings of(Radar radar) {
            return new Settings(radar.getMode(), radar.isHeadOn(), radar.getRange(), radar.getScanAngle(),
                    radar.showsPlayers(), radar.showsMobs(), radar.showsInvisibles(), radar.showsTeams(),
                    Math.max(2, Math.round(radar.getScanPeriod() * 20F)), radar.getSession());
        }
    }

    public static Frame snapshots(final Minecraft mc, final Radar radar) {
        if (!prepare(mc, radar)) return EMPTY;
        Settings next = settings;
        long tick = mc.level.getGameTime();
        if (sampledTick == tick) return cached;
        if (sampledTick != Long.MIN_VALUE && tick < sampledTick) TRACKER.reset();
        sampledTick = tick;

        if (TRACKER.isTracking()) {
            Entity entity = mc.level.getEntity(TRACKER.lockedId());
            Detection locked = detection(mc, entity, next, tick);
            if (locked != null && TRACKER.matches(locked) && Math.abs(locked.bearing) <= 90.0 + 1.0E-8
                    && visible(mc, entity)) {
                cached = TRACKER.advance(List.of(locked), next.mode, tick, next.periodTicks, next.angle, -1);
                return cached;
            }
            // Restore the configured search mode immediately on loss, without selecting a replacement lock.
            TRACKER.cancelTrack();
        }

        Vec3 origin = mc.player.position();
        Vec3 observerVelocity = velocity(mc.player);
        int preferred = preferredTarget(mc);
        // Retain the chosen contact before nearest contacts, even in a crowded loaded area.
        Comparator<Detection> ordering = Comparator
                .comparingInt((Detection d) -> d.contact.entityId == preferred ? 0
                        : d.contact.entityId == TRACKER.selectedId ? 1 : 2)
                .thenComparingDouble(d -> d.contact.position.distanceToSqr(origin))
                .thenComparingInt(d -> d.contact.entityId);
        PriorityQueue<Detection> nearest = new PriorityQueue<>(MAX_CONTACTS + 1, ordering.reversed());
        for (Entity entity : mc.level.entitiesForRendering()) {
            Detection detection = detection(mc, entity, next, tick);
            if (detection == null) continue;
            Contact contact = detection.contact;
            Vec3 offset = contact.position.subtract(origin);
            RadarGeometry.Relative relative = RadarGeometry.relative(offset, mc.player.getYRot());
            if (!RadarGeometry.withinScan(relative, next.range, next.angle)) continue;
            if (next.headOn && !RadarGeometry.isHeadOn(offset, contact.velocity, observerVelocity)) continue;
            if (next.mode == Radar.Mode.ACQ && detection.aimError > next.angle * 0.5) continue;
            nearest.add(detection);
            if (nearest.size() > MAX_CONTACTS) nearest.remove();
        }
        List<Detection> detections = new ArrayList<>(nearest);
        detections.sort(ordering);
        int selection = preferred;
        if (detections.stream().noneMatch(d -> d.contact.entityId == preferred)
                && mc.crosshairPickEntity instanceof LivingEntity pointed) selection = pointed.getId();
        cached = TRACKER.advance(detections, next.mode, tick, next.periodTicks, next.angle, selection);
        return cached;
    }

    /** Live validation for render-only TRK consumers; a selected TWS contact is never a manual lock. */
    public static @Nullable LivingEntity trackedTarget(final Minecraft mc, final Radar radar) {
        Frame frame = snapshots(mc, radar);
        if (!frame.tracking || !TRACKER.isTracking() || mc.level == null || mc.player == null) return null;
        Entity entity = mc.level.getEntity(frame.selectedId);
        // The scan is tick-cached, but death, removal, UUID reuse and changed sightlines can occur within a tick.
        Detection current = detection(mc, entity, settings, mc.level.getGameTime());
        return current != null && TRACKER.matches(current) && Math.abs(current.bearing) <= 90.0 + 1.0E-8
                && visible(mc, entity) ? (LivingEntity) entity : null;
    }

    /** Toggle a visual TRK lock. Capture is a visible 15-degree cone around the crosshair, independent of HDN. */
    public static boolean toggleTrack(final Minecraft mc, final Radar radar) {
        if (!prepare(mc, radar)) return false;
        // The renderer may have already requested this tick's normal frame before the input arrived.
        sampledTick = Long.MIN_VALUE;
        cached = EMPTY;
        if (TRACKER.isTracking()) return TRACKER.toggleTrack(List.of());
        Detection closest = null;
        long tick = mc.level.getGameTime();
        int pointedId = mc.crosshairPickEntity instanceof LivingEntity pointed ? pointed.getId() : -1;
        for (Entity entity : mc.level.entitiesForRendering()) {
            Detection candidate = detection(mc, entity, settings, tick);
            if (candidate == null || !captureEligible(candidate, pointedId) || !visible(mc, entity)) continue;
            boolean pointed = candidate.contact.entityId == pointedId;
            boolean previousPointed = closest != null && closest.contact.entityId == pointedId;
            if (closest == null || pointed && !previousPointed || pointed == previousPointed
                    && (candidate.aimError < closest.aimError || candidate.aimError == closest.aimError
                    && candidate.contact.position.distanceToSqr(mc.player.position())
                    < closest.contact.position.distanceToSqr(mc.player.position()))) closest = candidate;
        }
        return TRACKER.toggleTrack(closest == null ? List.of() : List.of(closest), pointedId);
    }

    private static boolean captureEligible(final Detection candidate, final int pointedId) {
        return Double.isFinite(candidate.aimError) && Double.isFinite(candidate.bearing)
                && Math.abs(candidate.bearing) <= 90.0
                && (candidate.contact.entityId == pointedId || candidate.aimError <= 15.0);
    }

    private static boolean visible(final Minecraft mc, final Entity target) {
        // LivingEntity.hasLineOfSight has a fixed 128-block cutoff, below the radar's configurable maximum.
        return mc.level.clip(new ClipContext(mc.player.getEyePosition(), target.getEyePosition(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    private static boolean prepare(final Minecraft mc, final Radar radar) {
        if (mc.level == null || mc.player == null || !mc.player.isAlive() || !radar.isEnabled()) {
            clear();
            return false;
        }
        Settings next = Settings.of(radar);
        if (world.get() != mc.level || player.get() != mc.player || !next.equals(settings)) {
            clear();
            world = new WeakReference<>(mc.level);
            player = new WeakReference<>(mc.player);
            settings = next;
        }
        return true;
    }

    /** Filters shared by ordinary search and manual tracking; beam/HDN/visibility policy belongs to their callers. */
    private static Detection detection(final Minecraft mc, final Entity entity, final Settings options, final long tick) {
        if (!(entity instanceof LivingEntity living) || entity == mc.player || entity.level() != mc.level
                || !living.isAlive() || living.isRemoved() || living.isSpectator()) return null;
        if (entity instanceof Player ? !options.players : !(entity instanceof Mob) || !options.mobs) return null;
        if (AntiBot.isBot(living)) return null;
        if (!options.invisibles && living.isInvisible()) return null;
        boolean ally = mc.player.isAlliedTo(living);
        if (ally && !options.teams) return null;
        Vec3 position = living.position();
        Vec3 offset = position.subtract(mc.player.position());
        if (!finite(position) || offset.lengthSqr() > options.range * (double) options.range) return null;
        RadarGeometry.Relative relative = RadarGeometry.relative(offset, mc.player.getYRot());
        if (relative == null) return null;
        Vec3 motion = velocity(living);
        if (!finite(motion)) return null;
        double aim = aimError(mc.player.getLookAngle(), living.getBoundingBox().getCenter().subtract(mc.player.getEyePosition()));
        if (!Double.isFinite(aim)) return null;
        String name = living.getDisplayName().getString();
        if (name.length() > 64) name = name.substring(0, 64);
        Contact contact = new Contact(living.getId(), name, position, motion, ally, tick);
        return new Detection(contact, relative.bearingDegrees(), aim, living.getUUID());
    }

    private static int preferredTarget(final Minecraft mc) {
        KillAura aura = Modules.enabled(KillAura.class);
        LivingEntity target = aura == null ? null : aura.getTarget();
        if (target != null && target.level() == mc.level && target.isAlive() && !target.isRemoved()) return target.getId();
        return mc.crosshairPickEntity instanceof LivingEntity pointed ? pointed.getId() : -1;
    }

    private static Vec3 velocity(final Entity entity) {
        return entity.position().subtract(entity.xo, entity.yo, entity.zo);
    }

    private static double aimError(final Vec3 look, final Vec3 offset) {
        double length = offset.length();
        if (!Double.isFinite(length) || length < 1.0E-8) return 180.0;
        double cosine = look.dot(offset.scale(1.0 / length));
        return Math.toDegrees(Math.acos(Math.clamp(cosine, -1.0, 1.0)));
    }

    private static boolean finite(final Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static void clear() {
        world.clear();
        player.clear();
        settings = null;
        sampledTick = Long.MIN_VALUE;
        cached = EMPTY;
        TRACKER.reset();
    }

    record Detection(Contact contact, double bearing, double aimError, UUID uuid) {
        Detection(Contact contact, double bearing, double aimError) {
            this(contact, bearing, aimError, new UUID(0L, contact.entityId));
        }
    }

    /** Pure scan/selection state, kept separate so skipped frames and stale echoes can be verified. */
    static final class Tracker {
        private final Map<Integer, Contact> echoes = new LinkedHashMap<>();
        private long lastTick = Long.MIN_VALUE;
        private int selectedId = -1;
        private int lockedId = -1;
        private UUID lockedUuid;

        boolean isTracking() { return lockedUuid != null; }
        int lockedId() { return lockedId; }

        boolean matches(final Detection detection) {
            return detection.contact.entityId == lockedId && detection.uuid.equals(lockedUuid);
        }

        boolean toggleTrack(final List<Detection> candidates) {
            return toggleTrack(candidates, -1);
        }

        boolean toggleTrack(final List<Detection> candidates, final int pointedId) {
            if (isTracking()) {
                cancelTrack();
                return true;
            }
            Detection closest = candidates.stream().filter(d -> captureEligible(d, pointedId))
                    .min(Comparator.comparingInt((Detection d) -> d.contact.entityId == pointedId ? 0 : 1)
                            .thenComparingDouble(Detection::aimError)
                            .thenComparingInt(d -> d.contact.entityId)).orElse(null);
            if (closest == null) return false;
            echoes.clear();
            lockedId = closest.contact.entityId;
            lockedUuid = closest.uuid;
            selectedId = lockedId;
            return true;
        }

        void cancelTrack() {
            lockedId = -1;
            lockedUuid = null;
            selectedId = -1;
            lastTick = Long.MIN_VALUE;
            echoes.clear();
        }

        Frame advance(final List<Detection> detections, final Radar.Mode mode, final long tick,
                      final int periodTicks, final double angle, final int preferredId) {
            if (lastTick != Long.MIN_VALUE && tick < lastTick) reset();
            if (isTracking()) {
                Detection locked = detections.stream().filter(this::matches).findFirst().orElse(null);
                if (locked != null) {
                    selectedId = lockedId;
                    lastTick = tick;
                    return new Frame(List.of(locked.contact), lockedId, 0.0, true);
                }
                cancelTrack();
            }
            double beam = sweep(tick, periodTicks, angle);
            if (mode == Radar.Mode.SRC) {
                HashSet<Integer> liveIds = new HashSet<>();
                for (Detection detection : detections) liveIds.add(detection.contact.entityId);
                echoes.values().removeIf(contact -> tick - contact.seenTick >= periodTicks
                        || tick < contact.seenTick || !liveIds.contains(contact.entityId));
                for (Detection detection : detections) {
                    boolean hit = lastTick == Long.MIN_VALUE ? Math.abs(detection.bearing - beam) <= 1.75
                            : swept(detection.bearing, lastTick, tick, periodTicks, angle);
                    if (hit) echoes.put(detection.contact.entityId, detection.contact);
                    while (echoes.size() > MAX_CONTACTS) echoes.remove(echoes.keySet().iterator().next());
                }
                selectedId = -1;
                lastTick = tick;
                return new Frame(new ArrayList<>(echoes.values()), -1, beam);
            }
            echoes.clear();
            Detection selected = null;
            if (mode == Radar.Mode.TWS) selected = find(detections, preferredId);
            if (selected == null) selected = find(detections, selectedId);
            if (selected == null) selected = detections.stream().min(Comparator
                    .comparingDouble(Detection::aimError).thenComparingInt(d -> d.contact.entityId)).orElse(null);
            selectedId = selected == null ? -1 : selected.contact.entityId;
            lastTick = tick;
            if (mode == Radar.Mode.ACQ) {
                return new Frame(selected == null ? List.of() : List.of(selected.contact), selectedId, 0.0);
            }
            return new Frame(detections.stream().limit(MAX_CONTACTS).map(Detection::contact).toList(), selectedId, beam);
        }

        private static Detection find(final List<Detection> detections, final int id) {
            if (id < 0) return null;
            for (Detection detection : detections) if (detection.contact.entityId == id) return detection;
            return null;
        }

        void reset() {
            cancelTrack();
        }
    }

    static double sweep(final long tick, final int periodTicks, final double angle) {
        double phase = Math.floorMod(tick, periodTicks) / (double) periodTicks;
        return angle * (phase <= 0.5 ? 2.0 * phase - 0.5 : 1.5 - 2.0 * phase);
    }

    /** Checks the whole travelled beam path, including a turnaround or a skipped complete sweep. */
    static boolean swept(final double bearing, final long from, final long to,
                         final int periodTicks, final double angle) {
        if (!Double.isFinite(bearing) || Math.abs(bearing) > angle * 0.5 + 1.0E-8 || to < from) return false;
        if (to - from >= periodTicks) return true;
        double start = from;
        double end = to;
        double halfPeriod = periodTicks * 0.5;
        for (int segment = 0; segment < 4 && start <= end; segment++) {
            double boundary = (Math.floor(start / halfPeriod) + 1.0) * halfPeriod;
            double stop = Math.min(end, boundary);
            double a = sweepAt(start, periodTicks, angle);
            double b = sweepAt(stop, periodTicks, angle);
            if (bearing >= Math.min(a, b) - 1.75 && bearing <= Math.max(a, b) + 1.75) return true;
            if (stop >= end) break;
            start = stop;
        }
        return false;
    }

    private static double sweepAt(final double tick, final int periodTicks, final double angle) {
        double phase = tick / periodTicks - Math.floor(tick / periodTicks);
        return angle * (phase <= 0.5 ? 2.0 * phase - 0.5 : 1.5 - 2.0 * phase);
    }
}
