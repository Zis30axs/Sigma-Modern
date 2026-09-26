package com.mentalfrostbyte.jello.anticheat;

import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.check.Detector;
import com.mentalfrostbyte.jello.anticheat.check.WindowTiming;
import com.mentalfrostbyte.jello.anticheat.observe.ObservedPlayers;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.mentalfrostbyte.jello.anticheat.observe.WorldProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * A detector with a flat stub world and a recording alert sink, plus a legal-player simulator to feed it -
 * everything the checks need, with no game running.
 */
final class Harness {

    static final int ID = 7;
    static final long TICK = WindowTiming.TICK_NANOS;
    /** Long enough after the player "appeared" that the spawn exemption is over. */
    static final long START = 10_000_000_000L;

    record Alert(String check, double level, String detail) {
    }

    /** A world that is flat ground at {@link #floorY} and otherwise empty, unless a test says otherwise. */
    static final class StubProbe implements WorldProbe {
        double floorY = 0.0;
        boolean loaded = true;
        boolean environment = false;
        double friction = 0.6;
        double lastHorizontalSlack = -1.0;
        double lastVerticalSlack = -1.0;

        @Override
        public boolean chunkLoaded(final double x, final double z) {
            return this.loaded;
        }

        @Override
        public double blockFriction(final double x, final double y, final double z) {
            return this.friction;
        }

        @Override
        public boolean supported(final double x, final double y, final double z, final double horizontalSlack,
                                 final double verticalSlack) {
            this.lastHorizontalSlack = horizontalSlack;
            this.lastVerticalSlack = verticalSlack;
            return y - verticalSlack <= this.floorY + 0.001 && y >= this.floorY - 1.0;
        }

        @Override
        public boolean unjudgedEnvironment(final double x, final double y, final double z) {
            return this.environment;
        }
    }

    final List<Alert> alerts = new ArrayList<>();
    final StubProbe probe = new StubProbe();
    final ObservedPlayers players;
    final TrackedPlayer player;
    CheckSettings settings = CheckSettings.balanced();

    Harness() {
        Detector detector = new Detector(new Detector.AlertSink() {
            @Override
            public void alert(final TrackedPlayer p, final String check, final double level, final String detail) {
                Harness.this.alerts.add(new Alert(check, level, detail));
            }

            @Override
            public void levelChanged(final TrackedPlayer p, final String check, final double level) {
            }
        });
        this.players = new ObservedPlayers(detector, this.probe);
        this.player = this.players.track(ID, UUID.randomUUID(), "Steve", 0L);
    }

    Harness withSettings(final CheckSettings newSettings) {
        this.settings = newSettings;
        return this;
    }

    Sample report(final long nanos, final double x, final double y, final double z, final boolean onGround,
                  final int flags, final double moveSpeed) {
        return this.players.onPosition(ID, x, y, z, onGround,
                new ObservedPlayers.Snapshot(flags, moveSpeed, Sample.DEFAULT_JUMP_STRENGTH, Sample.DEFAULT_GRAVITY, false),
                nanos, this.settings);
    }

    Sample report(final long nanos, final double x, final double y, final boolean onGround) {
        return this.report(nanos, x, y, 0.0, onGround, 0, Sample.DEFAULT_MOVE_SPEED);
    }

    double level(final String check) {
        return this.player.buffer(check).level();
    }

    boolean alerted(final String check) {
        return this.alerts.stream().anyMatch(alert -> alert.check().equals(check));
    }

    /** Positions the server rounds to, at the given step. */
    static double quantize(final double value, final double step) {
        return Math.round(value / step) * step;
    }

    /** One physics tick of a legal player. */
    record Point(int tick, double x, double y, boolean onGround) {
    }

    /**
     * A legal player running straight along +x on flat ground, simulated with the vanilla movement rules and
     * written independently of {@code MovementLimits} so the two check each other.
     */
    static final class LegalPlayer {
        double x;
        double y;
        double velocity;
        double vy;
        boolean onGround = true;
        /** Slipperiness of the floor: 0.6 for ordinary blocks, 0.98 for ice. */
        double friction = 0.6;

        /**
         * @param inputFactor 1 for free movement, 0.2 while using an item
         * @param jumpVelocity vertical speed a jump starts with (0.42 for a legal player)
         */
        void tick(final boolean sprint, final boolean hop, final double moveSpeed, final double inputFactor, final double jumpVelocity) {
            double input = 0.98 * inputFactor;
            if (hop && this.onGround) {
                this.vy = jumpVelocity;
                if (sprint) {
                    this.velocity += 0.2;
                }
            }

            double groundSpeed = this.friction > 0.6 ? moveSpeed * (0.216 / (this.friction * this.friction * this.friction)) : moveSpeed;
            double accel = this.onGround ? groundSpeed * input : (sprint ? 0.026 : 0.02) * input;
            double step = this.velocity + accel;
            this.x += step;
            this.velocity = step * (this.onGround ? this.friction * 0.91 : 0.91);

            double next = this.y + this.vy;
            if (next <= 0.0 && this.vy <= 0.0) {
                this.y = 0.0;
                this.vy = 0.0;
                this.onGround = true;
            } else {
                this.y = next;
                this.vy = (this.vy - 0.08) * 0.98;
                this.onGround = false;
            }
        }

        Point point(final int tick) {
            return new Point(tick, this.x, this.y, this.onGround);
        }
    }

    /** Ticks of a legal player who sprint-hops (or walks) the whole time. */
    static List<Point> run(final int ticks, final boolean sprint, final boolean hop, final double moveSpeed) {
        return run(ticks, sprint, hop, moveSpeed, 0.6);
    }

    /** As {@link #run(int, boolean, boolean, double)}, on a floor of the given slipperiness. */
    static List<Point> run(final int ticks, final boolean sprint, final boolean hop, final double moveSpeed, final double friction) {
        LegalPlayer player = new LegalPlayer();
        player.friction = friction;
        List<Point> points = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            player.tick(sprint, hop, moveSpeed, 1.0, 0.42);
            points.add(player.point(tick));
        }

        return points;
    }

    /** Reports every {@code stride}th tick, on schedule, at modern precision. */
    void feed(final List<Point> points, final int stride, final int flags, final double moveSpeed) {
        for (Point point : points) {
            if (point.tick() % stride == 0) {
                this.report(START + point.tick() * TICK, quantize(point.x(), 1.0 / 4096.0), quantize(point.y(), 1.0 / 4096.0),
                        0.0, point.onGround(), flags, moveSpeed);
            }
        }
    }

    /**
     * Reports with what the network does to a real stream: each report covers a random 1-4 ticks, arrives up
     * to a quarter second late, and every so often a few seconds of reports arrive in one burst after a stall.
     */
    void feedJittered(final List<Point> points, final long seed, final int flags, final double moveSpeed) {
        Random random = new Random(seed);
        long lastArrival = START;
        int tick = 0;
        long stallUntil = -1;
        while (tick < points.size()) {
            Point point = points.get(tick);
            long ideal = START + point.tick() * TICK;
            long arrival = ideal + (long) (random.nextDouble() * 250_000_000L);
            if (stallUntil < 0 && random.nextInt(400) == 0) {
                stallUntil = ideal + 1_000_000_000L;
            }

            if (stallUntil >= 0) {
                arrival = stallUntil;
                if (ideal >= stallUntil - 200_000_000L) {
                    stallUntil = -1;
                }
            }

            arrival = Math.max(arrival, lastArrival);
            lastArrival = arrival;
            this.report(arrival, quantize(point.x(), 1.0 / 4096.0), quantize(point.y(), 1.0 / 4096.0), 0.0,
                    point.onGround(), flags, moveSpeed);
            tick += 1 + random.nextInt(4);
        }
    }
}
