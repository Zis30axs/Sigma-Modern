package com.mentalfrostbyte.jello.event.impl.player.movement;

import com.mentalfrostbyte.jello.event.CancellableEvent;

/**
 * Fired when the local player jumps off the ground, before the upward impulse is applied.
 *
 * <p>Both fields are writable: {@link #setJumpPower(float)} changes the height, {@link #setYaw(float)}
 * changes the direction of the sprint boost that vanilla adds on top. Cancelling suppresses the jump.</p>
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
