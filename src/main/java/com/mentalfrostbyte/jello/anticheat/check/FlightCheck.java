package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.mentalfrostbyte.jello.anticheat.predict.MovementLimits;
import java.util.Locale;

/**
 * Flying, jumping too high, mid-air jumps and slow-fall gliding, judged over a whole airborne stretch rather
 * than per sample.
 *
 * <p>A stretch runs from the first sample that says "off the ground" to the next that says "on the ground".
 * Four things are checked across it:</p>
 * <ul>
 *   <li><b>Apex</b> - how far above the last grounded sample the player ever got. A jump tops out at about
 *       1.25 blocks; a further 0.6 is allowed because the last grounded sample can be a step-up older than
 *       the jump itself.</li>
 *   <li><b>Hover</b> - staying inside a 0.6-block band for a second and a half without touching anything.
 *       Gravity does not allow that: a falling player leaves any such band within a few ticks.</li>
 *   <li><b>AirJump</b> - falling further than one legal jump could have lifted the player, then climbing
 *       back up in the same unbroken airborne stretch. A hop always falls back to the ground it left, so
 *       its fall is bounded by the jump apex; a deeper fall means the player jumped again mid-air.</li>
 *   <li><b>Glide</b> - descending steadily for 1.5 seconds without approaching vanilla free-fall speed,
 *       when neither slow-falling particles nor custom gravity are present.</li>
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
    /** A rebound this tall counts as a climb back up (about half a jump); smaller bounces are noise. */
    private static final double AIR_JUMP_RISE_BASE = 0.65;
    /** Continuous descent window and upper fall-rate bound for slow-fall / glide detection. */
    private static final long GLIDE_NANOS = 30 * WindowTiming.TICK_NANOS;
    private static final double GLIDE_MAX_RATE_BPS = 3.5;
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
            state.airPeakY = sample.y();
            state.airTroughY = sample.y();
            state.descendedEnough = false;
            state.lastY = sample.y();
            state.bandStartNanos = sample.nanos();
            state.bandMin = sample.y();
            state.bandMax = sample.y();
            state.glideStartNanos = sample.nanos();
            state.glideStartY = sample.y();
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

        // Hovering, air-jumping or gliding over solid ground is the ground flag's business, not this check's.
        if (sample.supported()) {
            state.tainted = true;
            return;
        }

        // AirJump: a rebound only counts if the fall before it was deeper than one legal jump could produce.
        // A hop always falls back to (near) the ground it left, so its fall is bounded by the same limit the
        // Apex check uses; a deeper fall means the player was falling from a height and jumped again mid-air.
        // The fall is measured within one up-down cycle, which ends at every rebound (flagged or not), so a
        // hop chain down a slope cannot accumulate a false fall. A double-jump that turns around within one
        // jump-apex of its peak is deliberately not distinguished from a hop whose landing sample was missed.
        state.airPeakY = Math.max(state.airPeakY, sample.y());
        state.airTroughY = Math.min(state.airTroughY, sample.y());
        double airJumpDropLimit = (MovementLimits.jumpApex(sample.jumpStrength()) + STEP_ALLOWANCE + APEX_MARGIN)
                * settings.toleranceScale() + 3.0 * settings.quantStep();
        if (state.airPeakY - state.airTroughY > airJumpDropLimit) {
            state.descendedEnough = true;
        }
        if (sample.y() > state.lastY) {
            double rebound = sample.y() - state.airTroughY;
            double airJumpLimit = AIR_JUMP_RISE_BASE * settings.toleranceScale() + 3.0 * settings.quantStep();
            if (rebound > airJumpLimit) {
                if (state.descendedEnough) {
                    verdicts.flag(player, NAME, Math.min(3.0, 1.0 + (rebound - airJumpLimit)), String.format(Locale.ROOT,
                            "air-jumped %.2f blocks mid-fall", rebound));
                }
                state.airPeakY = sample.y();
                state.airTroughY = sample.y();
                state.descendedEnough = false;
            }
        }

        // Hover: staying inside a 0.6-block vertical band for 1.5s without support.
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

        // Glide / SlowFall: falling continuously without rising, yet staying far below free-fall velocity.
        if (sample.y() > state.lastY + 3.0 * settings.quantStep()) {
            state.glideStartNanos = sample.nanos();
            state.glideStartY = sample.y();
        } else {
            long glideElapsed = sample.nanos() - state.glideStartNanos;
            double drop = state.glideStartY - sample.y();
            if (glideElapsed >= GLIDE_NANOS) {
                double seconds = glideElapsed / 1.0E9;
                double fallRate = drop / seconds;
                // Only flag clear slow descent (above the hover band so hover and glide do not double-flag).
                if (drop > HOVER_BAND && fallRate < GLIDE_MAX_RATE_BPS / settings.toleranceScale()) {
                    verdicts.flag(player, NAME, 1.0, String.format(Locale.ROOT,
                            "gliding down %.2f blocks over %.1fs (%.1f b/s)", drop, seconds, fallRate));
                }
                state.glideStartNanos = sample.nanos();
                state.glideStartY = sample.y();
            }
        }

        state.lastY = sample.y();
    }

    private static final class State {
        private boolean airborne;
        private boolean tainted;
        private boolean launchKnown;
        private double launchY;
        private double maxY;
        private double airPeakY;
        private double airTroughY;
        private boolean descendedEnough;
        private double lastY;
        private double apexFlaggedRise = Double.NaN;
        private long bandStartNanos;
        private double bandMin;
        private double bandMax;
        private long glideStartNanos;
        private double glideStartY;
    }
}
