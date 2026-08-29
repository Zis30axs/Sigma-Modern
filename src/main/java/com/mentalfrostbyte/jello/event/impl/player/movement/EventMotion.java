package com.mentalfrostbyte.jello.event.impl.player.movement;

import com.mentalfrostbyte.jello.event.CancellableEvent;
import com.mentalfrostbyte.jello.event.EventState;

/**
 * The position and rotation the client is about to report to the server, fired from the local
 * player's movement packet path.
 *
 * <p>{@link EventState#PRE} carries the real values and every field is writable: whatever a listener
 * leaves behind is what gets sent, and what the client remembers as last reported. Cancelling sends
 * no movement packet this tick at all. {@link EventState#POST} is fired with the same instance after
 * the packet has gone out, so a listener can restore state it spoofed.</p>
 */
public class EventMotion extends CancellableEvent {

    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean onGround;

    public EventMotion(final double x, final double y, final double z,
                       final float yaw, final float pitch, final boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
    }

    public double getX() {
        return this.x;
    }

    public void setX(final double x) {
        this.x = x;
    }

    public double getY() {
        return this.y;
    }

    public void setY(final double y) {
        this.y = y;
    }

    public double getZ() {
        return this.z;
    }

    public void setZ(final double z) {
        this.z = z;
    }

    public float getYaw() {
        return this.yaw;
    }

    public void setYaw(final float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return this.pitch;
    }

    public void setPitch(final float pitch) {
        this.pitch = pitch;
    }

    public boolean isOnGround() {
        return this.onGround;
    }

    public void setOnGround(final boolean onGround) {
        this.onGround = onGround;
    }
}
