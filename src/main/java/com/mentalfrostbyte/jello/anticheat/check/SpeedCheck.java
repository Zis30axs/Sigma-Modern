package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.mentalfrostbyte.jello.anticheat.predict.MovementLimits;
import java.util.Locale;

/**
 * Horizontal speed: over a stretch of three seconds, nobody can cover more ground than the best possible
 * sprint-hopper on the fastest surface they touched.
 *
 * <p>Judged by window, not by sample. A sample spans a variable number of physics ticks and arrives with
 * network jitter, so any single interval can look far too fast; a window of sixty ticks averages that out,
 * and a burst allowance covers a player catching up after a lag spike. A window that touches an unjudgeable
 * moment (a teleport, a push, liquid, an elytra) is thrown away, not judged. A player who was never seen off
 * the ground is held to the walking ceiling, which is lower than the hopping one.</p>
 *
 * <p>Two over-the-ceiling windows in a row are needed before a flag (one on Strict), and the violation
 * level then follows Grim's shape: it grows on every offending window and drains on every clean one.</p>
 */
public final class SpeedCheck implements Check {

    public static final String NAME = "Speed";

    private static final long WINDOW_NANOS = 60 * WindowTiming.TICK_NANOS;
    private static final double CLEAN_DECAY = 0.5;
    /** Each segment's endpoints carry rounding error of up to a step per axis; allow three per segment. */
    private static final double QUANT_PER_SEGMENT = 3.0;

    /** Sprint multiplies the movement speed attribute by this; used when the attribute has not arrived yet. */
    private static final double SPRINT_FACTOR = 1.3;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(final CheckSettings settings) {
        return settings.speed();
    }

    @Override
    public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
        State state = player.state(NAME, State::new);

        if (!sample.judgeable()) {
            state.restart(null);
            state.strikes = 0;
            return;
        }

        if (state.last == null || sample.nanos() - state.last.nanos() > WindowTiming.GAP_RESET_NANOS) {
            state.restart(sample);
            return;
        }

        state.path += sample.horizontalDistanceTo(state.last);
        state.segments++;
        state.observe(sample, state.last);
        state.last = sample;

        long elapsed = sample.nanos() - state.windowStartNanos;
        if (elapsed < WINDOW_NANOS) {
            return;
        }

        double ceiling = MovementLimits.maxAveragePerTick(state.speed, state.minFriction, state.maxFriction,
                MovementLimits.NORMAL_INPUT, state.sprintSeen, state.jumpStrength, state.airborneSeen);
        double ticks = WindowTiming.ticks(elapsed, state.segments);
        double allowed = ceiling * settings.toleranceScale() * (ticks + settings.burstTicks())
                + state.segments * QUANT_PER_SEGMENT * settings.quantStep();

        if (state.path > allowed) {
            state.strikes++;
            if (state.strikes >= settings.strikesRequired()) {
                double excess = state.path / allowed - 1.0;
                verdicts.flag(player, NAME, Math.min(3.0, 1.0 + excess * 5.0), String.format(Locale.ROOT,
                        "%.1f blocks/s over %.1fs, ceiling %.1f", state.path / ticks * 20.0, ticks / 20.0,
                        allowed / ticks * 20.0));
                state.strikes = settings.strikesRequired() - 1;
            }
        } else {
            state.strikes = 0;
            verdicts.reward(player, NAME, CLEAN_DECAY);
        }

        state.restart(sample);
    }

    private static final class State {
        private Sample last;
        private long windowStartNanos;
        private double path;
        private int segments;
        private int strikes;

        // What the window has shown of the player; the ceiling is worked out from these at its end.
        private double speed;
        private double minFriction;
        private double maxFriction;
        private double jumpStrength;
        private boolean sprintSeen;
        private boolean airborneSeen;

        private void restart(final Sample start) {
            this.last = start;
            this.windowStartNanos = start == null ? 0L : start.nanos();
            this.path = 0.0;
            this.segments = 0;
            this.speed = Sample.DEFAULT_MOVE_SPEED;
            this.minFriction = Sample.DEFAULT_FRICTION;
            this.maxFriction = Sample.DEFAULT_FRICTION;
            this.jumpStrength = Sample.DEFAULT_JUMP_STRENGTH;
            this.sprintSeen = false;
            this.airborneSeen = start != null && !start.onGround();
        }

        /**
         * Sprint counts if the player shows it now or showed it a sample ago (the flag and the movement do not
         * arrive in lockstep); the speed used is the attribute, or the sprint multiple of the default if the
         * attribute has not caught up.
         */
        private void observe(final Sample sample, final Sample previous) {
            boolean sprinting = sample.has(PlayerFlags.SPRINTING) || previous.has(PlayerFlags.SPRINTING);
            double sampleSpeed = sample.moveSpeed();
            if (sprinting) {
                sampleSpeed = Math.max(sampleSpeed, Sample.DEFAULT_MOVE_SPEED * SPRINT_FACTOR);
                this.sprintSeen = true;
            }

            this.speed = Math.max(this.speed, sampleSpeed);
            this.minFriction = Math.min(this.minFriction, Math.min(sample.friction(), previous.friction()));
            this.maxFriction = Math.max(this.maxFriction, Math.max(sample.friction(), previous.friction()));
            this.jumpStrength = Math.max(this.jumpStrength, sample.jumpStrength());
            this.airborneSeen |= !sample.onGround();
        }
    }
}
