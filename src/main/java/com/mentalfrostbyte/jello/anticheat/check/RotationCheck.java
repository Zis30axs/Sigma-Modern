package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import java.util.Locale;

/**
 * Impossible vertical pitch values (Grim's BadPacketsA / Derp / Headless).
 *
 * <p>An unmodified Minecraft client strictly clamps pitch to {@code [-90, 90]} degrees. Even with 1/256-circle
 * protocol byte quantization, legal pitch values never exceed {@code ±90.5} degrees.</p>
 */
public final class RotationCheck implements Check {

    public static final String NAME = "Rotation";

    private static final float MAX_LEGAL_PITCH = 90.5F;
    private static final double CLEAN_DECAY = 0.05;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(final CheckSettings settings) {
        return settings.rotation();
    }

    @Override
    public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
        if (!sample.stateJudgeable() || Float.isNaN(sample.pitch())) {
            return;
        }

        float pitch = sample.pitch();
        if (Math.abs(pitch) > MAX_LEGAL_PITCH) {
            verdicts.flag(player, NAME, 1.5, String.format(Locale.ROOT,
                    "pitch %.1f outside [-90, 90]", pitch));
        } else {
            verdicts.reward(player, NAME, CLEAN_DECAY);
        }
    }
}
