package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;

/**
 * One kind of cheating the detector looks for. A check is a function of a player's samples: it is handed each
 * new sample as it arrives (already appended to {@link TrackedPlayer}) and keeps whatever state it needs in
 * {@link TrackedPlayer#state}, so one instance serves every player.
 */
public interface Check {

    /** The name the check is announced under, and the key its violation level is stored under. */
    String name();

    boolean enabled(CheckSettings settings);

    void onSample(TrackedPlayer player, Sample sample, CheckSettings settings, Verdicts verdicts);

    /** How a check reports what it found. */
    interface Verdicts {

        /** The player did something the check does not allow; {@code weight} is how strongly. */
        void flag(TrackedPlayer player, String check, double weight, String detail);

        /** The player behaved for a stretch; takes {@code decay} off the check's violation level. */
        void reward(TrackedPlayer player, String check, double decay);
    }
}
