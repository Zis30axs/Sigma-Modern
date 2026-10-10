package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.impl.gui.Rwr;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Samples known entities once per client tick, retaining only immutable values for the RWR renderer. */
public final class RwrThreats {
    public enum Type { CONTACT, AIM, ARROW, TRIDENT }

    /** Offset is world-relative to the local player; ETA is seconds, or infinity for player contacts. */
    public record Threat(int entityId, Vec3 offset, double distance, Type type, double etaSeconds) {}

    /** First solid hit along a world-space segment, in [0,1]; infinity means the segment is clear. */
    @FunctionalInterface
    public interface Obstruction {
        double fraction(Vec3 from, Vec3 to);
    }

    public static final int MAX_THREATS = 12;
    public static final int PREDICTION_TICKS = 40;
    private static final int MAX_PROJECTILES = 64;
    private static final double EPSILON = 1.0E-7;
    private static WeakReference<ClientLevel> cachedLevel = new WeakReference<>(null);
    private static WeakReference<Rwr> cachedOptions = new WeakReference<>(null);
    private static int cachedTick = -1;
    private static int cachedPlayerId;
    private static long cachedSession;
    private static float cachedRange;
    private static boolean cachedPlayers;
    private static boolean cachedProjectiles;
    private static List<Threat> cached = List.of();

    private RwrThreats() {}

    public static List<Threat> snapshots(Minecraft mc, Rwr options) {
        if (mc.level == null || mc.player == null || !mc.player.isAlive() || mc.player.isSpectator()) {
            cachedLevel.clear();
            cachedOptions.clear();
            cached = List.of();
            return cached;
        }
        if (cachedLevel.get() == mc.level && cachedOptions.get() == options
                && cachedTick == mc.player.tickCount && cachedPlayerId == mc.player.getId()
                && cachedSession == options.getSession() && cachedRange == options.getRange()
                && cachedPlayers == options.showsPlayers() && cachedProjectiles == options.showsProjectiles()) return cached;

        cachedLevel = new WeakReference<>(mc.level);
        cachedOptions = new WeakReference<>(options);
        cachedTick = mc.player.tickCount;
        cachedPlayerId = mc.player.getId();
        cachedSession = options.getSession();
        cachedRange = options.getRange();
        cachedPlayers = options.showsPlayers();
        cachedProjectiles = options.showsProjectiles();

        List<Threat> found = new ArrayList<>();
        Vec3 origin = mc.player.position();
        AABB target = mc.player.getBoundingBox();
        double rangeSquared = (double) cachedRange * cachedRange;
        if (cachedPlayers) {
            for (AbstractClientPlayer player : mc.level.players()) {
                if (player == mc.player || player.level() != mc.level || !player.isAlive()
                        || player.isRemoved() || player.isSpectator()) continue;
                Vec3 offset = player.position().subtract(origin);
                if (!finite(offset) || offset.lengthSqr() > rangeSquared) continue;
                Type type = aimsAt(player.getEyePosition(), player.getViewVector(1F), target.inflate(0.15),
                        cachedRange, (from, to) -> blockFraction(mc.level, player, from, to)) ? Type.AIM : Type.CONTACT;
                found.add(new Threat(player.getId(), offset, offset.length(), type, Double.POSITIVE_INFINITY));
            }
        }
        if (cachedProjectiles) {
            List<AbstractArrow> arrows = new ArrayList<>();
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof AbstractArrow arrow) || arrow.level() != mc.level
                        || !arrow.isAlive() || arrow.isRemoved() || arrow.getOwner() == mc.player
                        || arrow.isNoPhysics() || !arrow.isPushedByFluid()) continue;
                // AbstractArrow's public fluid-response flag is false exactly while its synced in-ground flag is set.
                Vec3 offset = arrow.position().subtract(origin);
                if (!finite(offset) || offset.lengthSqr() > rangeSquared || !finite(arrow.getDeltaMovement())
                        || arrow.getDeltaMovement().lengthSqr() < 0.0025) continue;
                arrows.add(arrow);
            }
            arrows.sort(Comparator.comparingDouble(arrow -> arrow.position().distanceToSqr(origin)));
            // Actual displacement avoids the small downwards velocity retained while standing on solid ground.
            Vec3 playerVelocity = origin.subtract(mc.player.getPosition(0F));
            for (int i = 0; i < Math.min(MAX_PROJECTILES, arrows.size()); i++) {
                AbstractArrow arrow = arrows.get(i);
                double drag = arrow.isInWater() && !(arrow instanceof ThrownTrident) ? 0.6 : 0.99;
                double impact = impactTicks(arrow.position(), arrow.getDeltaMovement(), target.inflate(0.3),
                        playerVelocity, arrow.getGravity(), drag, PREDICTION_TICKS,
                        (from, to) -> blockFraction(mc.level, arrow, from, to));
                if (!Double.isFinite(impact)) continue;
                Vec3 offset = arrow.position().subtract(origin);
                found.add(new Threat(arrow.getId(), offset, offset.length(),
                        arrow instanceof ThrownTrident ? Type.TRIDENT : Type.ARROW, impact / 20.0));
            }
        }
        cached = ordered(found);
        return cached;
    }

    /** Urgent paths remain visible even when many passive contacts surround the player. */
    static List<Threat> ordered(List<Threat> threats) {
        return threats.stream().sorted(Comparator.comparingInt((Threat threat) -> switch (threat.type) {
                    case ARROW, TRIDENT -> 0;
                    case AIM -> 1;
                    case CONTACT -> 2;
                }).thenComparingDouble(Threat::etaSeconds).thenComparingDouble(Threat::distance)
                .thenComparingInt(Threat::entityId)).limit(MAX_THREATS).toList();
    }

    /** A ray must reach the player's box before any wall. This is a viewing-direction estimate only. */
    public static boolean aimsAt(Vec3 eye, Vec3 direction, AABB target, double range, Obstruction obstruction) {
        if (!finite(eye) || !finite(direction) || !finite(target) || !Double.isFinite(range) || range <= 0
                || direction.lengthSqr() < EPSILON || !Double.isFinite(direction.lengthSqr())) return false;
        Vec3 end = eye.add(direction.normalize().scale(range));
        double hit = intersection(target, eye, end);
        return Double.isFinite(hit) && clearUntil(obstruction.fraction(eye, end), hit);
    }

    /**
     * Predicts a moving player's box against arrow segments, at most two seconds ahead. Arrow physics move first,
     * then apply drag and gravity. Target velocity and the current medium are estimates held constant in this window.
     * World clips run only for a trajectory that could hit, and every earlier segment must also remain unobstructed.
     */
    public static double impactTicks(Vec3 position, Vec3 velocity, AABB target, Vec3 targetVelocity,
                                     double gravity, double drag, int horizon, Obstruction obstruction) {
        if (!finite(position) || !finite(velocity) || !finite(target) || !finite(targetVelocity)
                || !Double.isFinite(gravity) || gravity < 0 || !Double.isFinite(drag) || drag <= 0 || drag > 1
                || horizon <= 0 || horizon > PREDICTION_TICKS || velocity.lengthSqr() < 0.0025
                || !Double.isFinite(velocity.lengthSqr())
                || target.getCenter().subtract(position).dot(velocity.subtract(targetVelocity)) <= EPSILON) {
            return Double.POSITIVE_INFINITY;
        }
        List<Vec3> path = new ArrayList<>(horizon + 1);
        path.add(position);
        Vec3 point = position;
        Vec3 movement = velocity;
        for (int tick = 0; tick < horizon; tick++) {
            Vec3 next = point.add(movement);
            if (!finite(next)) return Double.POSITIVE_INFINITY;
            path.add(next);
            double hit = intersection(target, point.subtract(targetVelocity.scale(tick)),
                    next.subtract(targetVelocity.scale(tick + 1)));
            if (Double.isFinite(hit)) {
                for (int segment = 0; segment <= tick; segment++) {
                    double allowed = segment == tick ? hit : 1.0;
                    if (!clearUntil(obstruction.fraction(path.get(segment), path.get(segment + 1)), allowed)) {
                        return Double.POSITIVE_INFINITY;
                    }
                }
                return tick + hit;
            }
            point = next;
            movement = movement.scale(drag).add(0, -gravity, 0);
        }
        return Double.POSITIVE_INFINITY;
    }

    private static double intersection(AABB target, Vec3 from, Vec3 to) {
        if (target.contains(from)) return 0;
        double lengthSquared = from.distanceToSqr(to);
        if (!Double.isFinite(lengthSquared) || lengthSquared <= EPSILON * EPSILON) return Double.POSITIVE_INFINITY;
        return target.clip(from, to).map(hit -> Math.sqrt(from.distanceToSqr(hit) / lengthSquared))
                .orElse(Double.POSITIVE_INFINITY);
    }

    private static boolean clearUntil(double obstruction, double fraction) {
        return obstruction == Double.POSITIVE_INFINITY
                || Double.isFinite(obstruction) && obstruction >= 0 && obstruction <= 1 && obstruction > fraction + EPSILON;
    }

    private static double blockFraction(ClientLevel level, Entity source, Vec3 from, Vec3 to) {
        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source));
        if (hit.getType() == HitResult.Type.MISS) return Double.POSITIVE_INFINITY;
        double lengthSquared = from.distanceToSqr(to);
        return lengthSquared <= EPSILON * EPSILON ? 0 : Math.clamp(Math.sqrt(from.distanceToSqr(hit.getLocation()) / lengthSquared), 0, 1);
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean finite(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                && box.minX < box.maxX && box.minY < box.maxY && box.minZ < box.maxZ;
    }
}
