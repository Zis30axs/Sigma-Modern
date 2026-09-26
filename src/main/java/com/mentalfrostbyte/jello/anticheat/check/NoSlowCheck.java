package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.mentalfrostbyte.jello.anticheat.predict.MovementLimits;
import java.util.Locale;

/**
 * Using an item scales the movement input by 0.2. A player who is eating, drawing a bow or blocking for a
 * stretch and yet keeps moving at ordinary speed is not slowed; that is Grim's NoSlow, judged here over a
 * window the way {@link SpeedCheck} judges speed.
 *
 * <p>Momentum from before the item was raised carries on for a moment (a sprint-hop can leave several
 * blocks of glide), so the first window of an unbroken use gets a one-off allowance for it. A later window
 * of the same use gets none.</p>
 */
public final class NoSlowCheck implements Check {

    public static final String NAME = "NoSlow";

    private static final long WINDOW_NANOS = 16 * WindowTiming.TICK_NANOS;
    /** Blocks of leftover momentum a sprint-hop can still carry when an item is raised. */
    private static final double CARRIED_MOMENTUM = 5.0;
    private static final double QUANT_PER_SEGMENT = 3.0;
    private static final double CLEAN_DECAY = 0.5;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(final CheckSettings settings) {
        return settings.noSlow();
    }

    @Override
    public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
        State state = player.state(NAME, State::new);

        if (!sample.judgeable() || !sample.has(PlayerFlags.USING_ITEM)) {
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
        state.minFriction = Math.min(state.minFriction, Math.min(sample.friction(), state.last.friction()));
        state.maxFriction = Math.max(state.maxFriction, Math.max(sample.friction(), state.last.friction()));
        state.jumpStrength = Math.max(state.jumpStrength, sample.jumpStrength());
        state.airborneSeen |= !sample.onGround();
        state.last = sample;

        long elapsed = sample.nanos() - state.windowStartNanos;
        if (elapsed < WINDOW_NANOS) {
            return;
        }

        double ceiling = MovementLimits.maxAveragePerTick(state.speed, state.minFriction, state.maxFriction,
                MovementLimits.USING_ITEM_INPUT, false, state.jumpStrength, state.airborneSeen);
        double ticks = WindowTiming.ticks(elapsed, state.segments);
        double allowed = ceiling * settings.toleranceScale() * (ticks + settings.burstTicks())
                + (state.firstWindow ? CARRIED_MOMENTUM : 0.0)
                + state.segments * QUANT_PER_SEGMENT * settings.quantStep();

        if (state.path > allowed) {
            verdicts.flag(player, NAME, Math.min(3.0, 1.0 + (state.path / allowed - 1.0) * 3.0), String.format(Locale.ROOT,
                    "%.1f blocks/s while using an item, ceiling %.1f", state.path / ticks * 20.0,
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
            this.minFriction = Sample.DEFAULT_FRICTION;
            this.maxFriction = Sample.DEFAULT_FRICTION;
            this.jumpStrength = Sample.DEFAULT_JUMP_STRENGTH;
            this.airborneSeen = !start.onGround();
        }

        /** The use ended or became unjudgeable: the next use starts fresh, momentum allowance included. */
        private void reset() {
            this.last = null;
            this.path = 0.0;
            this.segments = 0;
            this.firstWindow = true;
        }
    }
}
