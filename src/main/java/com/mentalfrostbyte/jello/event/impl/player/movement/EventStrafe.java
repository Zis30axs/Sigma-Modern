package com.mentalfrostbyte.jello.event.impl.player.movement;

import com.mentalfrostbyte.jello.event.Event;

/**
 * The yaw the local player's movement keys are turned by, fired from {@code Entity.moveRelative} each time the keys
 * become a push (on the ground, in the air, in water or lava).
 *
 * <p>Vanilla uses the player's own facing. A module that reports a different facing to the server - a KillAura's
 * silent rotation - sets that yaw here, so the player walks the way the server believes they are facing and the
 * movement stays the one a vanilla client facing that way would make. The sprint-jump boost has its own yaw, in
 * {@link EventJump}.</p>
 */
public class EventStrafe extends Event {

    private float yaw;

    public EventStrafe(final float yaw) {
        this.yaw = yaw;
    }

    public float getYaw() {
        return this.yaw;
    }

    public void setYaw(final float yaw) {
        this.yaw = yaw;
    }
}
