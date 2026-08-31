package com.mentalfrostbyte.jello.gui.base.animations;

/**
 * Small reversible UI animation used by Sigma presentations.
 *
 * <p>This keeps the useful API shape of the original Sigma animation helper while replacing its
 * wall-clock {@code Date} bookkeeping with monotonic {@link System#nanoTime()}. Changing direction
 * preserves the current progress, so a hover can reverse immediately without snapping.</p>
 */
public final class Animation {

    private final int duration;
    private final int reverseDuration;

    private Direction direction;
    private long transitionStartNanos;
    private float transitionStartProgress;

    public Animation(final int duration, final int reverseDuration) {
        this(duration, reverseDuration, Direction.FORWARDS);
    }

    public Animation(final int duration, final int reverseDuration, final Direction direction) {
        if (duration <= 0 || reverseDuration <= 0) {
            throw new IllegalArgumentException("Animation durations must be positive");
        }

        this.duration = duration;
        this.reverseDuration = reverseDuration;
        this.direction = direction;
        this.transitionStartProgress = 0.0F;
        this.transitionStartNanos = System.nanoTime();
    }

    public int getDuration() {
        return this.duration;
    }

    public int getReverseDuration() {
        return this.reverseDuration;
    }

    public Direction getDirection() {
        return this.direction;
    }

    /** Reverses or resumes the animation without changing its visible progress. */
    public void changeDirection(final Direction direction) {
        if (this.direction == direction) {
            return;
        }

        this.transitionStartProgress = this.calcPercent();
        this.transitionStartNanos = System.nanoTime();
        this.direction = direction;
    }

    /**
     * Compatibility method for older Sigma UI code. The supplied progress becomes the new current value
     * and animation continues from it in the current direction.
     */
    public void updateStartTime(final float progress) {
        this.setProgress(progress);
    }

    public void setProgress(final float progress) {
        this.transitionStartProgress = clamp01(progress);
        this.transitionStartNanos = System.nanoTime();
    }

    /** Current linear progress in {@code [0, 1]}. Apply an easing curve at the call site when desired. */
    public float calcPercent() {
        float elapsedMillis = (System.nanoTime() - this.transitionStartNanos) / 1_000_000.0F;
        float progress;
        if (this.direction == Direction.FORWARDS) {
            progress = this.transitionStartProgress + elapsedMillis / this.duration;
        } else {
            progress = this.transitionStartProgress - elapsedMillis / this.reverseDuration;
        }
        return clamp01(progress);
    }

    public boolean isFinished() {
        float progress = this.calcPercent();
        return this.direction == Direction.FORWARDS ? progress >= 1.0F : progress <= 0.0F;
    }

    private static float clamp01(final float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    public enum Direction {
        FORWARDS,
        BACKWARDS
    }
}
