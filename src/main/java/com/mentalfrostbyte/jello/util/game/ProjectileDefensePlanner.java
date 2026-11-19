package com.mentalfrostbyte.jello.util.game;

import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Incoming-flight prediction and lateral evasion adapted from LiquidBounce AutoDodge / DodgePlanner,
 * Copyright (c) 2015-2026 CCBlueX, GPL-3.0-or-later. Evaluates the player's input and both sides of a
 * volley against the same flight paths. Sigma additionally evaluates a slower, shielded path.
 */
public final class ProjectileDefensePlanner {
    public enum Action { KEEP, BLOCK, DODGE }
    public record Plan(Action action, Vec2 input) { }
    record Segment(Vec3 from, Vec3 to, double duration, float margin, boolean stopped) { }
    record Flight(List<Segment> segments, boolean blockable) { }
    record Walk(List<AABB> boxes, boolean leftEdge) { }

    @FunctionalInterface
    interface Walker {
        Walk predict(Vec2 input, float useSpeed);
    }

    private static final List<Vec2> DIRECTIONS = List.of(new Vec2(1, 0), new Vec2(-1, 0),
            new Vec2(0, 1), new Vec2(0, -1), new Vec2(1, 1).normalized(), new Vec2(-1, 1).normalized(),
            new Vec2(1, -1).normalized(), new Vec2(-1, -1).normalized());
    private ProjectileDefensePlanner() { }

    static Plan choose(List<Flight> flights, Vec2 input, float yaw, int shieldDelay, float shieldSpeed,
                       Walker walker, BiFunction<Vec3, Vec3, @Nullable Vec3> unobstructed) {
        Plan keep = new Plan(Action.KEEP, input);
        if (flights.isEmpty()) return keep;
        Walk baseline = walker.predict(input, 1F);
        Risk original = assess(flights, baseline, -1, unobstructed);
        if (original.hits == 0) return keep;
        if (shieldDelay >= 0 && original.hasBlockableHit) {
            Walk slowed = walker.predict(input, shieldSpeed);
            if (!slowed.leftEdge && assess(flights, slowed, shieldDelay, unobstructed).hits == 0) {
                return new Plan(Action.BLOCK, input);
            }
        }

        Risk best = original;
        Plan plan = keep;
        double bestPreference = Double.NEGATIVE_INFINITY;
        Vec3 incoming = original.direction;
        for (Vec2 direction : DIRECTIONS) {
            Walk candidate = walker.predict(direction, 1F);
            if (candidate.leftEdge) continue;
            Risk risk = assess(flights, candidate, -1, unobstructed);
            if (risk.compareSafety(original) <= 0) continue;
            // Favour a perpendicular escape, then the one closest to the player's intended heading.
            Vec3 world = heading(direction, yaw);
            double preference = 1 - Math.abs(world.dot(incoming)) + direction.dot(input);
            int safer = risk.compareSafety(best);
            if (safer > 0 || (safer == 0 && preference > bestPreference)) {
                best = risk;
                bestPreference = preference;
                plan = new Plan(Action.DODGE, direction);
            }
        }
        return plan;
    }

    static Vec3 heading(Vec2 input, float yaw) {
        return new Vec3(input.x, 0, input.y).yRot((float) Math.toRadians(-yaw));
    }

    private record Risk(int hits, double first, Vec3 direction, boolean hasBlockableHit) {
        int compareSafety(Risk other) {
            int count = Integer.compare(other.hits, hits);
            return count != 0 ? count : Double.compare(first, other.first);
        }
    }

    private static Risk assess(List<Flight> flights, Walk walk, int shieldDelay,
                               BiFunction<Vec3, Vec3, @Nullable Vec3> unobstructed) {
        int count = 0;
        boolean hasBlockableHit = false;
        double first = Double.POSITIVE_INFINITY;
        Vec3 direction = Vec3.ZERO;
        for (Flight flight : flights) {
            int ticks = Math.min(flight.segments.size(), walk.boxes.size() - 1);
            for (int tick = 0; tick < ticks; tick++) {
                Segment segment = flight.segments.get(tick);
                double time = crossing(walk.boxes.get(tick), walk.boxes.get(tick + 1), segment, unobstructed);
                if (!Double.isFinite(time)) continue;
                double arrival = tick + time;
                if (!(flight.blockable && shieldDelay >= 0 && arrival >= shieldDelay)) {
                    count++;
                    hasBlockableHit |= flight.blockable;
                    if (arrival < first) {
                        first = arrival;
                        direction = segment.to.subtract(segment.from).multiply(1, 0, 1).normalize();
                    }
                }
                break;
            }
        }
        return new Risk(count, first, direction, hasBlockableHit);
    }

    /** Intersect in the moving box's frame. A wall or an intervening entity ends the segment partway through a tick. */
    static double crossing(AABB box, AABB nextBox, Segment segment,
                           BiFunction<Vec3, Vec3, @Nullable Vec3> unobstructed) {
        Vec3 offset = nextBox.getMinPosition().subtract(box.getMinPosition()).scale(segment.duration);
        Vec3 end = segment.to.subtract(offset);
        Vec3 contact = TrajectoryWorld.segmentBox(box, segment.from, end);
        boolean graze = contact == null;
        if (graze) contact = TrajectoryWorld.segmentBox(box.inflate(segment.margin), segment.from, end);
        if (contact == null) return Double.POSITIVE_INFINITY;
        double length = segment.from.distanceTo(end);
        double fraction = length < 1.0E-10 ? 0 : segment.from.distanceTo(contact) / length;
        if (segment.stopped && fraction >= 1 - 1.0E-8) return Double.POSITIVE_INFINITY;
        if (graze) {
            Vec3 delta = offset.scale(fraction);
            Vec3 outside = contact.add(delta);
            AABB atContact = box.move(delta);
            Vec3 clear = unobstructed.apply(outside, atContact.getCenter());
            if (clear == null || TrajectoryWorld.segmentBox(atContact, outside, clear) == null) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return fraction * segment.duration;
    }
}
