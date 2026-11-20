package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import java.util.Locale;

/**
 * Walking flat across a liquid surface without solid support (Jesus / WaterWalk).
 *
 * <p>Because solid blocks, lily pads, frost-walker ice, boats and shulkers all satisfy
 * {@link Sample#supported()}, a player who moves horizontally across water or lava with no support, stays
 * vertically flat within a tight band for 1.5 seconds, and is not swimming/gliding is walking on liquid.</p>
 */
public final class JesusCheck implements Check {

    public static final String NAME = "Jesus";

    private static final long WINDOW_NANOS = 30 * WindowTiming.TICK_NANOS;
    /** Vertical oscillation allowed for a Jesus walk; normal bobbing in water exceeds this. */
    private static final double FLAT_BAND = 0.18;
    /** Minimum horizontal speed (blocks/s) over liquid to avoid flagging stationary floating. */
    private static final double MIN_SPEED_BPS = 2.6;
    private static final double CLEAN_DECAY = 0.35;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(final CheckSettings settings) {
        return settings.jesus();
    }

    @Override
    public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
        State state = player.state(NAME, State::new);

        boolean liquidSurface = !sample.exempt()
                && sample.chunkLoaded()
                && sample.overLiquid()
                && !sample.supported()
                && (sample.flags() & PlayerFlags.UNJUDGED) == 0;

        if (!liquidSurface) {
            if (sample.chunkLoaded() && !sample.exempt()) {
                verdicts.reward(player, NAME, CLEAN_DECAY * 0.2);
            }
            state.reset();
            return;
        }

        if (state.last == null || sample.nanos() - state.last.nanos() > WindowTiming.GAP_RESET_NANOS) {
            state.begin(sample);
            return;
        }

        state.path += sample.horizontalDistanceTo(state.last);
        state.segments++;
        state.minY = Math.min(state.minY, sample.y());
        state.maxY = Math.max(state.maxY, sample.y());
        state.groundedSamples += sample.onGround() ? 1 : 0;
        state.last = sample;

        double ySpan = state.maxY - state.minY;
        if (ySpan > FLAT_BAND + 3.0 * settings.quantStep()) {
            verdicts.reward(player, NAME, CLEAN_DECAY);
            state.begin(sample);
            return;
        }

        long elapsed = sample.nanos() - state.windowStartNanos;
        if (elapsed < WINDOW_NANOS) {
            return;
        }

        double ticks = WindowTiming.ticks(elapsed, state.segments);
        double speedBps = state.path / ticks * 20.0;
        double requiredSpeed = MIN_SPEED_BPS * settings.toleranceScale();

        if (speedBps >= requiredSpeed || (state.groundedSamples >= 3 && state.path >= 1.0)) {
            verdicts.flag(player, NAME, Math.min(3.0, 1.0 + (state.groundedSamples >= 3 ? 0.5 : 0.0)),
                    String.format(Locale.ROOT, "walking on liquid at %.1f blocks/s (y-span %.2f)", speedBps, ySpan));
        } else {
            verdicts.reward(player, NAME, CLEAN_DECAY);
        }

        state.begin(sample);
    }

    private static final class State {
        private Sample last;
        private long windowStartNanos;
        private double path;
        private int segments;
        private double minY;
        private double maxY;
        private int groundedSamples;

        private void begin(final Sample start) {
            this.last = start;
            this.windowStartNanos = start.nanos();
            this.path = 0.0;
            this.segments = 0;
            this.minY = start.y();
            this.maxY = start.y();
            this.groundedSamples = start.onGround() ? 1 : 0;
        }

        private void reset() {
            this.last = null;
            this.path = 0.0;
            this.segments = 0;
            this.groundedSamples = 0;
        }
    }
}
