package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;

/**
 * The on-ground flag against what is actually under the player's feet - Grim's NoFall and GroundSpoof.
 *
 * <p>The flag is whatever the player's own client claims, so it can be forged. Two lies are caught:
 * claiming to be on the ground with nothing under the feet (the classic no-fall), and claiming to be in the
 * air, motionless, for several reports in a row while standing on something solid.</p>
 *
 * <p>How much slack "under the feet" gets is decided when the sample is taken: the client resolves vertical
 * collision before it moves sideways, so a moving player can legitimately report ground a step past a
 * ledge, and the slack grows with how far they moved. A standing player gets almost none.</p>
 */
public final class GroundSpoofCheck implements Check {

    public static final String NAME = "GroundSpoof";

    /** Motionless samples in the air over solid ground before the reverse lie is called. */
    private static final int AIR_REST_SAMPLES = 3;
    private static final long AIR_REST_NANOS = 4_000_000_000L;
    private static final double REST_EPSILON = 5.0E-4;
    /** Grim's default drain for this check is smaller, but its samples come every tick; ours come every other. */
    private static final double CLEAN_DECAY = 0.05;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(final CheckSettings settings) {
        return settings.groundSpoof();
    }

    @Override
    public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
        State state = player.state(NAME, State::new);

        if (!sample.judgeable()) {
            state.resetRest();
            return;
        }

        if (sample.onGround()) {
            state.resetRest();
            if (sample.supported()) {
                verdicts.reward(player, NAME, CLEAN_DECAY);
            } else {
                verdicts.flag(player, NAME, 1.0, "claimed to be on the ground with nothing under the feet");
            }

            return;
        }

        boolean resting = sample.supported() && state.last != null && Math.abs(sample.y() - state.last.y()) < REST_EPSILON
                && sample.horizontalDistanceTo(state.last) < REST_EPSILON;
        if (!resting) {
            state.resetRest();
            state.last = sample;
            return;
        }

        if (state.restCount == 0) {
            state.restStartNanos = state.last.nanos();
        }

        state.restCount++;
        state.last = sample;
        if (state.restCount >= AIR_REST_SAMPLES && sample.nanos() - state.restStartNanos >= AIR_REST_NANOS) {
            verdicts.flag(player, NAME, 1.0, "claimed to be in the air while standing still on solid ground");
            state.resetRest();
        }
    }

    private static final class State {
        private Sample last;
        private int restCount;
        private long restStartNanos;

        private void resetRest() {
            this.restCount = 0;
            this.last = null;
        }
    }
}
