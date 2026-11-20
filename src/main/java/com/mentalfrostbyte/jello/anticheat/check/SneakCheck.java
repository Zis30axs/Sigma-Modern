package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.mentalfrostbyte.jello.anticheat.predict.MovementLimits;
import java.util.Locale;

/**
 * Moving too fast while sneaking (FastSneak / Scaffold-Sneak).
 *
 * <p>Sneaking scales movement input by {@link Sample#sneakingSpeed()} (0.3 by default, up to 0.75 with Swift
 * Sneak III) and prevents sprinting. Like {@link NoSlowCheck}, this check judges continuous sneaking over a
 * window and grants a one-off momentum allowance on the first window so sprint-hopping into a crouch does not
 * false-flag.</p>
 */
public final class SneakCheck implements Check {

    public static final String NAME = "Sneak";

    private static final long WINDOW_NANOS = 20 * WindowTiming.TICK_NANOS;
    /** Blocks of leftover momentum a sprint-hop can carry into the start of a crouch. */
    private static final double CARRIED_MOMENTUM = 4.0;
    private static final double QUANT_PER_SEGMENT = 3.0;
    private static final double CLEAN_DECAY = 0.5;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(final CheckSettings settings) {
        return settings.sneak();
    }

    @Override
    public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
        State state = player.state(NAME, State::new);

        if (!sample.judgeable() || !sample.has(PlayerFlags.SNEAKING)) {
            state.reset();
            return;
        }

        if (state.last == null || sample.nanos() - state.last.nanos() > WindowTiming.GAP_RESET_NANOS) {
            state.reset();
            state.begin(sample);
            return;
        }

        state.path += sample.horizontalDistanceTo(state.last);
        state.segments++;
        state.speed = Math.max(state.speed, sample.moveSpeed());
        state.sneakingFactor = Math.max(state.sneakingFactor, Math.min(1.0, Math.max(Sample.DEFAULT_SNEAKING_SPEED, sample.sneakingSpeed())));
        state.minFriction = Math.min(state.minFriction, Math.min(sample.friction(), state.last.friction()));
        state.maxFriction = Math.max(state.maxFriction, Math.max(sample.friction(), state.last.friction()));
        state.jumpStrength = Math.max(state.jumpStrength, sample.jumpStrength());
        state.airborneSeen |= !sample.onGround();
        state.last = sample;

        long elapsed = sample.nanos() - state.windowStartNanos;
        if (elapsed < WINDOW_NANOS) {
            return;
        }

        // If a server raised SNEAKING_SPEED to 1.0, sneaking imposes no slowdown at all.
        if (state.sneakingFactor >= 0.99) {
            state.firstWindow = false;
            state.begin(sample);
            return;
        }

        double ceiling = MovementLimits.maxAveragePerTick(state.speed, state.minFriction, state.maxFriction,
                state.sneakingFactor, false, state.jumpStrength, state.airborneSeen);
        double ticks = WindowTiming.ticks(elapsed, state.segments);
        double allowed = ceiling * settings.toleranceScale() * (ticks + settings.burstTicks())
                + (state.firstWindow ? CARRIED_MOMENTUM : 0.0)
                + state.segments * QUANT_PER_SEGMENT * settings.quantStep();

        if (state.path > allowed) {
            verdicts.flag(player, NAME, Math.min(3.0, 1.0 + (state.path / allowed - 1.0) * 3.0), String.format(Locale.ROOT,
                    "%.1f blocks/s while sneaking, ceiling %.1f", state.path / ticks * 20.0,
                    allowed / ticks * 20.0));
        } else {
            verdicts.reward(player, NAME, CLEAN_DECAY);
        }

        state.firstWindow = false;
        state.begin(sample);
    }

    private static final class State {
        private Sample last;
        private long windowStartNanos;
        private double path;
        private int segments;
        private double speed;
        private double sneakingFactor;
        private double minFriction;
        private double maxFriction;
        private double jumpStrength;
        private boolean airborneSeen;
        private boolean firstWindow = true;

        private void begin(final Sample start) {
            this.last = start;
            this.windowStartNanos = start.nanos();
            this.path = 0.0;
            this.segments = 0;
            this.speed = Sample.DEFAULT_MOVE_SPEED;
            this.sneakingFactor = Math.max(Sample.DEFAULT_SNEAKING_SPEED, start.sneakingSpeed());
            this.minFriction = Sample.DEFAULT_FRICTION;
            this.maxFriction = Sample.DEFAULT_FRICTION;
            this.jumpStrength = Sample.DEFAULT_JUMP_STRENGTH;
            this.airborneSeen = !start.onGround();
        }

        private void reset() {
            this.last = null;
            this.path = 0.0;
            this.segments = 0;
            this.firstWindow = true;
        }
    }
}
