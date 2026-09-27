package com.mentalfrostbyte.jello.module.impl.misc;

import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where the {@link FakePlayer} goes, one game tick at a time: pure maths over vanilla's movement numbers, so it can be
 * tested without a game. Whatever it does, it never leaves the circle of {@code radius} round its anchor (horizontally;
 * flying also stays within {@code radius} of the ground).
 */
final class FakePlayerBrain {

    /** Vanilla's ground speeds, blocks a tick: walking and sprinting. */
    static final double WALK = 0.2158;
    static final double SPRINT = 0.2806;
    /** Creative flight, roughly. */
    static final double FLY = 0.33;
    static final double JUMP = 0.42;
    static final double GRAVITY = 0.08;
    static final double DRAG = 0.98;
    /** How much of a knockback push is left after each tick. */
    static final double PUSH_DECAY = 0.55;

    /** The height of the ground's top at a column; the brain stands on it. */
    interface Ground {
        double at(double x, double z);
    }

    private final Vec3 anchor;
    private final Random random;

    private Vec3 position;
    private double verticalSpeed;
    private boolean onGround = true;
    private float yaw;
    private float pitch;
    private Vec3 push = Vec3.ZERO;
    private @Nullable Vec3 waypoint;
    // CombatSimulation
    private int strafe = 1;
    private int strafeTicks;
    private int backOff;
    private int swingCooldown;
    private boolean swung;

    FakePlayerBrain(final Vec3 anchor, final long seed) {
        this.anchor = anchor;
        this.position = anchor;
        this.random = new Random(seed);
    }

    Vec3 position() {
        return this.position;
    }

    float yaw() {
        return this.yaw;
    }

    float pitch() {
        return this.pitch;
    }

    boolean onGround() {
        return this.onGround;
    }

    /** Whether it swung its arm this tick (CombatSimulation, when in reach). */
    boolean swung() {
        return this.swung;
    }

    Vec3 anchor() {
        return this.anchor;
    }

    void tick(final FakePlayer.Mode mode, final double speed, final double radius, final @Nullable Vec3 opponent, final Ground ground) {
        this.swung = false;
        switch (mode) {
            case MOVING -> this.walk(WALK * speed, radius, ground, false);
            case JUMPING -> this.walk(SPRINT * speed, radius, ground, true);
            case FLYING -> this.fly(FLY * speed, radius, ground);
            case COMBAT_SIMULATION -> {
                if (opponent == null) {
                    this.walk(WALK * speed, radius, ground, false);
                } else {
                    this.fight(SPRINT * speed, radius, opponent, ground);
                }
            }
        }
        this.push = this.push.scale(PUSH_DECAY);
    }

    /** Hit by someone facing {@code attackerYaw}: pushed away from them and off the ground, as vanilla knockback does. */
    void knockback(final float attackerYaw) {
        double angle = Math.toRadians(attackerYaw);
        this.push = new Vec3(-Math.sin(angle) * 0.4, 0.0, Math.cos(angle) * 0.4);
        if (this.onGround) {
            this.verticalSpeed = 0.36;
            this.onGround = false;
        }
    }

    // ------------------------------------------------------------------------------------------------ modes

    /** Moving and Jumping: walks from one random spot in the circle to the next, facing where it goes. */
    private void walk(final double speed, final double radius, final Ground ground, final boolean jumpy) {
        if (this.waypoint == null || horizontal(this.position, this.waypoint) < 0.6 || horizontal(this.anchor, this.waypoint) > radius) {
            this.waypoint = this.randomPoint(radius * 0.9);
        }
        Vec3 heading = flatUnit(this.waypoint.subtract(this.position));
        this.face(heading, 0.0F);
        boolean jump = jumpy && this.onGround && this.random.nextInt(6) == 0;
        this.step(heading.scale(speed), jump, radius, ground);
    }

    /** Flying: from one random spot in the air above the circle to the next, no gravity. */
    private void fly(final double speed, final double radius, final Ground ground) {
        double floor = ground.at(this.position.x, this.position.z);
        double ceiling = floor + Math.min(radius, 6.0);
        if (this.waypoint == null || this.position.distanceTo(this.waypoint) < 0.6 || horizontal(this.anchor, this.waypoint) > radius
                || this.waypoint.y < floor + 1.0 || this.waypoint.y > ceiling) {
            Vec3 flat = this.randomPoint(radius * 0.9);
            this.waypoint = new Vec3(flat.x, floor + 1.5 + this.random.nextDouble() * (ceiling - floor - 1.5), flat.z);
        }
        Vec3 toward = this.waypoint.subtract(this.position);
        Vec3 heading = toward.length() < 1.0E-6 ? Vec3.ZERO : toward.normalize();
        this.face(flatUnit(toward), (float) -Math.toDegrees(Math.asin(Mth.clamp(heading.y, -1.0, 1.0))));
        Vec3 next = this.position.add(heading.scale(speed)).add(this.push);
        this.onGround = false;
        this.verticalSpeed = 0.0;
        this.position = this.clamp(new Vec3(next.x, Mth.clamp(next.y, floor, ceiling), next.z), radius);
    }

    /**
     * CombatSimulation: keeps at fighting distance from the opponent, strafing round them and switching sides every
     * second or two, jumps now and then, swings when in reach and backs off for a few ticks after each swing (a w-tap).
     * Still never leaves the circle: an opponent who walks off is watched from its edge.
     */
    private void fight(final double speed, final double radius, final Vec3 opponent, final Ground ground) {
        Vec3 to = opponent.subtract(this.position);
        double distance = Math.sqrt(to.x * to.x + to.z * to.z);
        Vec3 facing = flatUnit(to);
        this.face(facing, (float) -Math.toDegrees(Math.atan2(to.y, Math.max(distance, 1.0E-3))));

        if (--this.strafeTicks <= 0) {
            this.strafe = -this.strafe;
            this.strafeTicks = 15 + this.random.nextInt(25);
        }
        double forward;
        if (this.backOff > 0) {
            this.backOff--;
            forward = -1.0;
        } else if (distance > 3.0) {
            forward = 1.0;
        } else if (distance < 2.2) {
            forward = -0.5;
        } else {
            forward = 0.0;
        }
        Vec3 side = new Vec3(-facing.z, 0.0, facing.x).scale(this.strafe * 0.8);
        Vec3 move = facing.scale(forward).add(side);
        Vec3 velocity = move.lengthSqr() < 1.0E-9 ? Vec3.ZERO : move.normalize().scale(speed);

        if (distance < 3.3 && --this.swingCooldown <= 0) {
            this.swung = true;
            this.swingCooldown = 10 + this.random.nextInt(4);
            this.backOff = 4;
        }
        boolean jump = this.onGround && this.random.nextInt(25) == 0;
        this.step(velocity, jump, radius, ground);
        // Look at them from where it ended up, as a player keeps their crosshair on the opponent.
        Vec3 after = opponent.subtract(this.position);
        this.face(flatUnit(after), (float) -Math.toDegrees(Math.atan2(after.y, Math.max(Math.sqrt(after.x * after.x + after.z * after.z), 1.0E-3))));
    }

    // ---------------------------------------------------------------------------------------------- physics

    /** One tick on the ground's terms: horizontal {@code velocity} plus any push, vanilla jump arcs, then the circle. */
    private void step(final Vec3 velocity, final boolean jump, final double radius, final Ground ground) {
        double x = this.position.x + velocity.x + this.push.x;
        double z = this.position.z + velocity.z + this.push.z;
        double floor = ground.at(x, z);
        double y = this.position.y;
        if (this.onGround && jump) {
            this.verticalSpeed = JUMP;
            this.onGround = false;
        }
        if (this.onGround) {
            y = floor;
        } else {
            y += this.verticalSpeed;
            this.verticalSpeed = (this.verticalSpeed - GRAVITY) * DRAG;
            if (y <= floor) {
                y = floor;
                this.verticalSpeed = 0.0;
                this.onGround = true;
            }
        }
        this.position = this.clamp(new Vec3(x, y, z), radius);
    }

    private void face(final Vec3 flatHeading, final float pitch) {
        if (flatHeading.lengthSqr() > 1.0E-9) {
            this.yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(flatHeading.z, flatHeading.x)) - 90.0F);
        }
        this.pitch = Mth.clamp(pitch, -90.0F, 90.0F);
    }

    /** {@code point} pulled back horizontally onto the circle of {@code radius} round the anchor if it is outside. */
    Vec3 clamp(final Vec3 point, final double radius) {
        double dx = point.x - this.anchor.x;
        double dz = point.z - this.anchor.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= radius || distance < 1.0E-9) {
            return point;
        }
        double scale = radius / distance;
        return new Vec3(this.anchor.x + dx * scale, point.y, this.anchor.z + dz * scale);
    }

    private Vec3 randomPoint(final double radius) {
        double angle = this.random.nextDouble() * Math.PI * 2.0;
        double distance = Math.sqrt(this.random.nextDouble()) * radius;
        return new Vec3(this.anchor.x + Math.cos(angle) * distance, this.position.y, this.anchor.z + Math.sin(angle) * distance);
    }

    static double horizontal(final Vec3 a, final Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Vec3 flatUnit(final Vec3 vector) {
        double length = Math.sqrt(vector.x * vector.x + vector.z * vector.z);
        return length < 1.0E-9 ? Vec3.ZERO : new Vec3(vector.x / length, 0.0, vector.z / length);
    }
}
