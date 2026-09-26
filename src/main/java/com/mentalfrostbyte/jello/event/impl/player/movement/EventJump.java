package com.mentalfrostbyte.jello.event.impl.player.movement;

import com.mentalfrostbyte.jello.event.CancellableEvent;

/**
 * Fired twice when the local player jumps off the ground.
 *
 * <p>{@link com.mentalfrostbyte.jello.event.EventState#PRE PRE}, before the upward impulse is applied: both
 * fields are writable - {@link #setJumpPower(float)} changes the height, {@link #setYaw(float)} changes the
 * direction of the sprint boost vanilla adds on top - and cancelling suppresses the jump.</p>
 *
 * <p>{@link com.mentalfrostbyte.jello.event.EventState#POST POST}, once the impulse is in the player's velocity
 * (only if a jump actually happened): the fields are what was used, and a listener that wants to shape the jump
 * sets the player's velocity directly. Cancelling a POST does nothing.</p>
 */
public class EventJump extends CancellableEvent {

    private float jumpPower;
    private float yaw;

    public EventJump(final float jumpPower, final float yaw) {
        this.jumpPower = jumpPower;
        this.yaw = yaw;
    }

    public float getJumpPower() {
        return this.jumpPower;
    }

    public void setJumpPower(final float jumpPower) {
        this.jumpPower = jumpPower;
    }

    public float getYaw() {
        return this.yaw;
    }

    public void setYaw(final float yaw) {
        this.yaw = yaw;
    }
}
