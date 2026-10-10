package com.mentalfrostbyte.jello.event.impl.player.movement;

import com.mentalfrostbyte.jello.event.Event;

/**
 * Whether the local player should stop at an unsupported edge. The initial value is vanilla's
 * sneak state; changing it uses the normal edge collision checks without changing the held keys,
 * movement speed, pose, or the sneak state reported to the server.
 */
public final class EventSafeWalk extends Event {

    private boolean safe;

    public EventSafeWalk(final boolean safe) {
        this.safe = safe;
    }

    public boolean isSafe() {
        return this.safe;
    }

    public void setSafe(final boolean safe) {
        this.safe = safe;
    }
}
