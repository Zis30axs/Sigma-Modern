package com.mentalfrostbyte.jello.gui;

import com.mentalfrostbyte.jello.module.impl.combat.aimassist.AimStyleModel;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Random;

/** Local angular practice arena. Only movements associated with an attack enter the model. */
public final class AimAssistTrainingSession {
    private static final double SAMPLE_INTERVAL = 1.0 / 40.0;
    private static final double ATTACK_INTERVAL = 0.16;
    private static final int PENDING_LIMIT = 16;
    private final AimStyleModel model;
    private final Random random;
    private final ArrayDeque<Observation> pending = new ArrayDeque<>();
    private boolean active, held, seeded;
    private int scenario, hits, misses, targetIndex;
    private double time, targetAge, attackAge, sampleAge;
    private double targetYaw, targetPitch, lastTargetYaw, lastTargetPitch;
    private double aimYaw, aimPitch, lastYaw, lastPitch, previousYawVelocity, previousPitchVelocity;

    private record Observation(AimStyleModel.Input input, AimStyleModel.Velocity velocity, double time) {}

    public AimAssistTrainingSession(AimStyleModel model) { this(model, new Random()); }

    AimAssistTrainingSession(AimStyleModel model, Random random) {
        this.model = Objects.requireNonNull(model, "model");
        this.random = Objects.requireNonNull(random, "random");
        this.retarget();
    }

    public boolean active() { return this.active; }
    public int scenario() { return this.scenario; }
    public int hits() { return this.hits; }
    public int misses() { return this.misses; }
    public double targetYaw() { return this.targetYaw; }
    public double targetPitch() { return this.targetPitch; }
    public int targetIndex() { return this.targetIndex; }
    public static double switchYaw(int index) { return (index - 1) * 23.0; }
    public static double switchPitch(int index) { return index == 1 ? -8.0 : 5.0; }

    public void start() {
        this.active = true;
        this.clearInput();
    }

    public void pause() {
        this.active = false;
        this.clearInput();
    }

    public void selectScenario(int scenario) {
        if (scenario < 0 || scenario >= AimStyleModel.SCENARIO_COUNT) throw new IllegalArgumentException("scenario");
        this.scenario = scenario;
        this.time = 0.0;
        this.clearInput();
        this.retarget();
    }

    public void reset() {
        this.pause();
        this.model.reset();
        this.hits = this.misses = 0;
        this.time = 0.0;
        this.retarget();
    }

    /** Seconds, degrees, and degrees/second throughout; discontinuities never become training samples. */
    public void update(double seconds, double yaw, double pitch, boolean inside) {
        if (!this.active) return;
        if (!Double.isFinite(seconds) || seconds <= 0.0 || seconds > 0.15
            || !Double.isFinite(yaw) || !Double.isFinite(pitch)) {
            this.clearInput();
            return;
        }
        this.time += seconds;
        while (!this.pending.isEmpty() && this.pending.getFirst().time() < this.time - 0.4) this.pending.removeFirst();
        this.targetAge += seconds;
        this.attackAge += seconds;
        this.aimYaw = yaw;
        this.aimPitch = pitch;
        if ((this.scenario == 0 && this.targetAge > 3.0) || (this.scenario == 3 && this.targetAge > 1.7)) {
            this.retarget();
        }
        if (this.scenario == 1) {
            this.targetYaw = Math.sin(this.time * 1.8) * 25.0;
            this.targetPitch = 2.0;
        } else if (this.scenario == 2) {
            this.targetYaw = Math.sin(this.time * 1.3) * 20.0;
            this.targetPitch = 7.0 - Math.abs(Math.sin(this.time * 2.1)) * 18.0;
        }
        if (!inside) {
            this.clearInput();
            return;
        }
        if (!this.seeded) {
            this.seeded = true;
            this.seed();
            return;
        }
        this.sampleAge += seconds;
        if (this.sampleAge >= SAMPLE_INTERVAL) {
            double yawVelocity = (yaw - this.lastYaw) / this.sampleAge;
            double pitchVelocity = (pitch - this.lastPitch) / this.sampleAge;
            // Cursor warps and implausibly fast jumps must not be learned as clipped flicks.
            if (!Double.isFinite(yawVelocity) || !Double.isFinite(pitchVelocity)
                || Math.hypot(yawVelocity, pitchVelocity) > AimStyleModel.MAX_VELOCITY) {
                this.clearInput();
                return;
            }
            double targetYawVelocity = (this.targetYaw - this.lastTargetYaw) / this.sampleAge;
            double targetPitchVelocity = (this.targetPitch - this.lastTargetPitch) / this.sampleAge;
            double yawError = this.lastTargetYaw - this.lastYaw;
            double pitchError = this.lastTargetPitch - this.lastPitch;
            Observation observation = new Observation(new AimStyleModel.Input(yawError, pitchError,
                targetYawVelocity, targetPitchVelocity, this.previousYawVelocity, this.previousPitchVelocity),
                new AimStyleModel.Velocity(yawVelocity, pitchVelocity), this.time);
            // Idle pointing far away carries no aiming intent. Near-target stops teach deceleration.
            boolean meaningful = Math.hypot(yawVelocity, pitchVelocity) > 0.5
                || Math.hypot(yawError, pitchError) < 3.0;
            if (meaningful) {
                if (this.held) this.observe(observation);
                else {
                    if (this.pending.size() == PENDING_LIMIT) this.pending.removeFirst();
                    this.pending.addLast(observation);
                }
            }
            this.previousYawVelocity = yawVelocity;
            this.previousPitchVelocity = pitchVelocity;
            this.seed();
        }
        if (this.held && this.attackAge >= ATTACK_INTERVAL) this.fire();
    }

    /** Commit the short lead-in to this actual attack; hovering alone never trains the model. */
    public void pressAttack(double yaw, double pitch) {
        if (!this.active || !this.seeded || !Double.isFinite(yaw) || !Double.isFinite(pitch)) return;
        this.aimYaw = yaw;
        this.aimPitch = pitch;
        while (!this.pending.isEmpty()) this.observe(this.pending.removeFirst());
        this.held = true;
        this.fire();
    }

    public void releaseAttack() { this.held = false; }
    public void leaveArena() { this.clearInput(); }

    private void fire() {
        this.attackAge = 0.0;
        if (Math.abs(this.aimYaw - this.targetYaw) <= 2.0 && Math.abs(this.aimPitch - this.targetPitch) <= 3.0) {
            this.hits++;
            if (this.scenario == 0 || this.scenario == 3) this.retarget();
        } else this.misses++;
    }

    private void observe(Observation observation) {
        this.model.observe(observation.input(), observation.velocity(), this.scenario);
    }

    private void retarget() {
        this.targetAge = 0.0;
        if (this.scenario == 0) {
            this.targetYaw = this.random.nextDouble(-27.0, 27.0);
            this.targetPitch = this.random.nextDouble(-11.0, 11.0);
        } else if (this.scenario == 3) {
            this.targetIndex = (this.targetIndex + 1 + this.random.nextInt(2)) % 3;
            this.targetYaw = switchYaw(this.targetIndex);
            this.targetPitch = switchPitch(this.targetIndex);
        } else {
            this.targetYaw = 0.0;
            this.targetPitch = this.scenario == 1 ? 2.0 : 7.0;
        }
        this.clearSamples();
    }

    private void seed() {
        this.lastYaw = this.aimYaw;
        this.lastPitch = this.aimPitch;
        this.lastTargetYaw = this.targetYaw;
        this.lastTargetPitch = this.targetPitch;
        this.sampleAge = 0.0;
    }

    private void clearInput() {
        this.held = false;
        this.attackAge = 0.0;
        this.clearSamples();
    }

    private void clearSamples() {
        this.seeded = false;
        this.sampleAge = 0.0;
        this.previousYawVelocity = this.previousPitchVelocity = 0.0;
        this.pending.clear();
    }
}
