package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.PlayerFlags;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.mentalfrostbyte.jello.anticheat.predict.MovementLimits;
import java.util.Locale;

/**
 * Illegal sprinting states and backwards omni-sprinting (Grim's SprintA / SprintB / SprintC).
 *
 * <p>Catches three patterns that an unmodified client cannot sustain:</p>
 * <ul>
 *   <li>Sprinting continuously while using an item (eating, drawing a bow, blocking).</li>
 *   <li>Sprinting continuously while sneaking on solid ground.</li>
 *   <li>Sprinting continuously backwards relative to head yaw at a speed above ordinary walking.</li>
 * </ul>
 */
public final class SprintCheck implements Check {

    public static final String NAME = "Sprint";

    /** Minimum duration an illegal flag combination must persist before flagging (absorbs packet ordering). */
    private static final long ILLEGAL_STATE_NANOS = 12 * WindowTiming.TICK_NANOS;
    private static final double MIN_STATE_DISTANCE = 0.3;

    /** Window for backwards omni-sprint detection. */
    private static final long OMNI_WINDOW_NANOS = 30 * WindowTiming.TICK_NANOS;
    /** Dot product below -0.45 means moving > ~117 degrees away from the facing direction. */
    private static final double BACKWARD_DOT_THRESHOLD = -0.45;
    private static final double MIN_SEGMENT_MOVE = 0.05;
    private static final double CLEAN_DECAY = 0.35;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(final CheckSettings settings) {
        return settings.sprint();
    }

    @Override
    public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
        State state = player.state(NAME, State::new);

        if (!sample.judgeable() || !sample.has(PlayerFlags.SPRINTING)) {
            if (sample.judgeable() && !sample.has(PlayerFlags.SPRINTING)) {
                verdicts.reward(player, NAME, CLEAN_DECAY * 0.25);
            }
            state.resetAll();
            return;
        }

        if (state.last == null || sample.nanos() - state.last.nanos() > WindowTiming.GAP_RESET_NANOS) {
            state.resetAll();
            state.begin(sample);
            return;
        }

        double stepDist = sample.horizontalDistanceTo(state.last);

        // 1. Illegal state: sprinting while using an item.
        if (sample.has(PlayerFlags.USING_ITEM)) {
            if (state.useSprintStart == Long.MIN_VALUE) {
                state.useSprintStart = state.last.nanos();
                state.useSprintDist = 0.0;
            }
            state.useSprintDist += stepDist;
            long elapsed = sample.nanos() - state.useSprintStart;
            if (elapsed >= ILLEGAL_STATE_NANOS && state.useSprintDist >= MIN_STATE_DISTANCE) {
                verdicts.flag(player, NAME, 1.0, String.format(Locale.ROOT,
                        "sprinting while using an item for %.1fs", elapsed / 1.0E9));
                state.useSprintStart = sample.nanos();
                state.useSprintDist = 0.0;
            }
        } else {
            state.useSprintStart = Long.MIN_VALUE;
            state.useSprintDist = 0.0;
        }

        // 2. Illegal state: sprinting while sneaking on ground.
        if (sample.has(PlayerFlags.SNEAKING) && sample.onGround()) {
            if (state.sneakSprintStart == Long.MIN_VALUE) {
                state.sneakSprintStart = state.last.nanos();
                state.sneakSprintDist = 0.0;
            }
            state.sneakSprintDist += stepDist;
            long elapsed = sample.nanos() - state.sneakSprintStart;
            if (elapsed >= ILLEGAL_STATE_NANOS && state.sneakSprintDist >= MIN_STATE_DISTANCE) {
                verdicts.flag(player, NAME, 1.0, String.format(Locale.ROOT,
                        "sprinting while sneaking for %.1fs", elapsed / 1.0E9));
                state.sneakSprintStart = sample.nanos();
                state.sneakSprintDist = 0.0;
            }
        } else {
            state.sneakSprintStart = Long.MIN_VALUE;
            state.sneakSprintDist = 0.0;
        }

        // 3. OmniSprint: sprinting continuously backwards relative to look yaw.
        if (!Float.isNaN(sample.yaw())) {
            if (stepDist >= MIN_SEGMENT_MOVE) {
                double dx = sample.x() - state.last.x();
                double dz = sample.z() - state.last.z();
                double rad = Math.toRadians(sample.yaw());
                double lookX = -Math.sin(rad);
                double lookZ = Math.cos(rad);
                double dot = (dx * lookX + dz * lookZ) / stepDist;
                state.weightedDot += dot * stepDist;
                state.omniDist += stepDist;
            }
            state.omniSegments++;
            state.omniSpeed = Math.max(state.omniSpeed, sample.moveSpeed());
            state.omniMinFriction = Math.min(state.omniMinFriction, Math.min(sample.friction(), state.last.friction()));
            state.omniMaxFriction = Math.max(state.omniMaxFriction, Math.max(sample.friction(), state.last.friction()));
            state.omniJumpStrength = Math.max(state.omniJumpStrength, sample.jumpStrength());
            state.omniAirborne |= !sample.onGround();

            long omniElapsed = sample.nanos() - state.omniStartNanos;
            if (omniElapsed >= OMNI_WINDOW_NANOS) {
                double ticks = WindowTiming.ticks(omniElapsed, state.omniSegments);
                // Compare against non-sprinting walk ceiling (dividing out the 1.3x sprint attribute modifier).
                double walkAttr = state.omniSpeed / 1.3;
                double walkCeiling = MovementLimits.maxAveragePerTick(walkAttr, state.omniMinFriction, state.omniMaxFriction,
                        MovementLimits.NORMAL_INPUT, false, state.omniJumpStrength, state.omniAirborne);
                double walkAllowed = walkCeiling * settings.toleranceScale() * ticks;
                double meanDot = state.omniDist > 0.0 ? state.weightedDot / state.omniDist : 1.0;

                if (state.omniDist > walkAllowed && meanDot < BACKWARD_DOT_THRESHOLD) {
                    verdicts.flag(player, NAME, 1.0, String.format(Locale.ROOT,
                            "sprinting backwards at %.1f blocks/s (align %.2f)",
                            state.omniDist / ticks * 20.0, meanDot));
                } else {
                    verdicts.reward(player, NAME, CLEAN_DECAY);
                }

                state.resetOmni(sample);
            }
        } else {
            state.resetOmni(sample);
        }

        state.last = sample;
    }

    private static final class State {
        private Sample last;
        private long useSprintStart = Long.MIN_VALUE;
        private double useSprintDist;
        private long sneakSprintStart = Long.MIN_VALUE;
        private double sneakSprintDist;

        private long omniStartNanos;
        private double omniDist;
        private double weightedDot;
        private int omniSegments;
        private double omniSpeed;
        private double omniMinFriction;
        private double omniMaxFriction;
        private double omniJumpStrength;
        private boolean omniAirborne;

        private void begin(final Sample start) {
            this.last = start;
            this.resetOmni(start);
        }

        private void resetOmni(final Sample start) {
            this.omniStartNanos = start.nanos();
            this.omniDist = 0.0;
            this.weightedDot = 0.0;
            this.omniSegments = 0;
            this.omniSpeed = Sample.DEFAULT_MOVE_SPEED;
            this.omniMinFriction = Sample.DEFAULT_FRICTION;
            this.omniMaxFriction = Sample.DEFAULT_FRICTION;
            this.omniJumpStrength = Sample.DEFAULT_JUMP_STRENGTH;
            this.omniAirborne = !start.onGround();
        }

        private void resetAll() {
            this.last = null;
            this.useSprintStart = Long.MIN_VALUE;
            this.useSprintDist = 0.0;
            this.sneakSprintStart = Long.MIN_VALUE;
            this.sneakSprintDist = 0.0;
            this.omniDist = 0.0;
            this.weightedDot = 0.0;
            this.omniSegments = 0;
        }
    }
}
