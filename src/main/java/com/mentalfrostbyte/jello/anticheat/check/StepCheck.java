package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import java.util.Locale;

/**
 * Impossible ground-to-ground vertical step-ups (Grim's Step / HighStep).
 *
 * <p>A vanilla player can step up at most {@code stepHeight} (0.6 blocks by default) without leaving the
 * ground. Because samples arrive roughly every two ticks, a player sprinting up 0.5-block stairs can cover
 * up to two half-steps (~1.0 block) while remaining on the ground across a single sample interval, provided
 * they also move horizontally across the stair treads. Stepping higher than two steps in a single short
 * grounded interval, or stepping above {@code stepHeight} with almost no horizontal movement, cannot be
 * produced by an unmodified client.</p>
 */
public final class StepCheck implements Check {

    public static final String NAME = "Step";

    /** Only judge tight consecutive grounded samples; longer gaps can hide a full jump cycle. */
    private static final long MAX_STEP_GAP_NANOS = 6 * WindowTiming.TICK_NANOS;
    private static final double STEP_MARGIN = 0.05;
    /** Minimum horizontal travel required to legitimately climb two consecutive 0.5-block stairs. */
    private static final double STAIR_MIN_HORIZONTAL = 0.35;
    private static final double CLEAN_DECAY = 0.15;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(final CheckSettings settings) {
        return settings.step();
    }

    @Override
    public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
        State state = player.state(NAME, State::new);

        if (!sample.judgeable() || !sample.onGround()) {
            state.lastGrounded = null;
            return;
        }

        Sample previous = state.lastGrounded;
        state.lastGrounded = sample;
        if (previous == null) {
            return;
        }

        long elapsed = sample.nanos() - previous.nanos();
        if (elapsed <= 0L || elapsed > MAX_STEP_GAP_NANOS) {
            return;
        }

        double dy = sample.y() - previous.y();
        if (dy <= 0.0) {
            verdicts.reward(player, NAME, CLEAN_DECAY);
            return;
        }

        double stepHeight = Math.max(sample.stepHeight(), previous.stepHeight());
        double quantAllowance = 3.0 * settings.quantStep();
        double singleStepLimit = (stepHeight + STEP_MARGIN) * settings.toleranceScale() + quantAllowance;
        double doubleStairLimit = Math.max(1.25, stepHeight * 2.0 + STEP_MARGIN) * settings.toleranceScale() + quantAllowance;
        double horizontal = sample.horizontalDistanceTo(previous);

        if (dy > doubleStairLimit) {
            verdicts.flag(player, NAME, Math.min(3.0, 1.0 + (dy - doubleStairLimit)), String.format(Locale.ROOT,
                    "stepped up %.2f blocks while grounded, limit %.2f", dy, doubleStairLimit));
        } else if (dy > singleStepLimit && horizontal < STAIR_MIN_HORIZONTAL) {
            verdicts.flag(player, NAME, Math.min(2.5, 1.0 + (dy - singleStepLimit)), String.format(Locale.ROOT,
                    "stepped up %.2f blocks with %.2f horizontal travel, limit %.2f", dy, horizontal, singleStepLimit));
        } else {
            verdicts.reward(player, NAME, CLEAN_DECAY);
        }
    }

    private static final class State {
        private Sample lastGrounded;
    }
}
