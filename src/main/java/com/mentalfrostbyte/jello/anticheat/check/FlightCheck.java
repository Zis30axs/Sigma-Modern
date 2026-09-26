package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.mentalfrostbyte.jello.anticheat.predict.MovementLimits;
import java.util.Locale;

/**
 * Flying and jumping too high, judged over a whole airborne stretch rather than per sample.
 *
 * <p>A stretch runs from the first sample that says "off the ground" to the next that says "on the ground".
 * Two things are checked across it:</p>
 * <ul>
 *   <li><b>Apex</b> - how far above the last grounded sample the player ever got. A jump tops out at about
 *       1.25 blocks; a further 0.6 is allowed because the last grounded sample can be a step-up older than
 *       the jump itself.</li>
 *   <li><b>Hover</b> - staying inside a 0.6-block band for a second and a half without touching anything.
 *       Gravity does not allow that: a falling player leaves any such band within a few ticks.</li>
 * </ul>
 * <p>A stretch is dropped, unjudged, if anything in it is one of the states v1 does not model, if the player
 * shows potion particles (levitation and slow falling cannot be seen, only their particles), or if the
 * gravity attribute is not the ordinary one.</p>
 */
public final class FlightCheck implements Check {

    public static final String NAME = "Flight";

    /** The last grounded sample can predate the jump by a step-up; steps are at most this tall. */
    private static final double STEP_ALLOWANCE = 0.6;
    private static final double APEX_MARGIN = 0.05;
    private static final double APEX_REFLAG_STEP = 1.0;
    private static final long HOVER_NANOS = 30 * WindowTiming.TICK_NANOS;
    private static final double HOVER_BAND = 0.6;
    private static final double CLEAN_DECAY = 0.25;
    private static final double GRAVITY_EPSILON = 1.0E-6;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(final CheckSettings settings) {
        return settings.flight();
    }

    @Override
    public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
        State state = player.state(NAME, State::new);

        if (sample.onGround()) {
            if (state.airborne && !state.tainted) {
                verdicts.reward(player, NAME, CLEAN_DECAY);
            }

            state.airborne = false;
            state.launchKnown = sample.judgeable();
            state.launchY = sample.y();
            state.apexFlaggedRise = Double.NaN;
            return;
        }

        if (!state.airborne) {
            state.airborne = true;
            state.tainted = false;
            state.apexFlaggedRise = Double.NaN;
            state.maxY = sample.y();
            state.bandStartNanos = sample.nanos();
            state.bandMin = sample.y();
            state.bandMax = sample.y();
        }

        if (!sample.judgeable() || sample.unknownEffects() || Math.abs(sample.gravity() - Sample.DEFAULT_GRAVITY) > GRAVITY_EPSILON) {
            state.tainted = true;
        }

        if (state.tainted) {
            return;
        }

        state.maxY = Math.max(state.maxY, sample.y());

        if (state.launchKnown) {
            double limit = (MovementLimits.jumpApex(sample.jumpStrength()) + STEP_ALLOWANCE + APEX_MARGIN) * settings.toleranceScale()
                    + 3.0 * settings.quantStep();
            double rise = state.maxY - state.launchY;
            if (rise > limit && (Double.isNaN(state.apexFlaggedRise) || rise >= state.apexFlaggedRise + APEX_REFLAG_STEP)) {
                verdicts.flag(player, NAME, Math.min(3.0, 1.0 + (rise - limit)), String.format(Locale.ROOT,
                        "rose %.2f blocks off the ground, limit %.2f", rise, limit));
                // Flagged again only for each further block gained, so one high jump is one flag but a player
                // who keeps climbing keeps being flagged.
                state.apexFlaggedRise = rise;
            }
        }

        // Hovering over solid ground is the ground flag's business, not this check's.
        if (sample.supported()) {
            state.tainted = true;
            return;
        }

        state.bandMin = Math.min(state.bandMin, sample.y());
        state.bandMax = Math.max(state.bandMax, sample.y());
        if (state.bandMax - state.bandMin > HOVER_BAND) {
            state.bandStartNanos = sample.nanos();
            state.bandMin = sample.y();
            state.bandMax = sample.y();
        } else if (sample.nanos() - state.bandStartNanos >= HOVER_NANOS) {
            verdicts.flag(player, NAME, 1.0, String.format(Locale.ROOT, "hovering within %.2f blocks for %.1fs",
                    state.bandMax - state.bandMin, (sample.nanos() - state.bandStartNanos) / 1.0E9));
            // A player who keeps hovering is flagged again after another full window.
            state.bandStartNanos = sample.nanos();
            state.bandMin = sample.y();
            state.bandMax = sample.y();
        }
    }

    private static final class State {
        private boolean airborne;
        private boolean tainted;
        private boolean launchKnown;
        private double launchY;
        private double maxY;
        private double apexFlaggedRise = Double.NaN;
        private long bandStartNanos;
        private double bandMin;
        private double bandMax;
    }
}
