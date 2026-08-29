package com.mentalfrostbyte.jello.event.impl.player;

import com.mentalfrostbyte.jello.event.CancellableEvent;
import com.mentalfrostbyte.jello.event.EventState;

/**
 * The local player's tick, fired at the head ({@link EventState#PRE}) and tail
 * ({@link EventState#POST}). Cancelling the PRE event skips the vanilla tick entirely, including the
 * movement and rotation packets it sends.
 *
 * <p>This is the general purpose "once per tick, player exists, world exists" hook most modules hang
 * their logic off.</p>
 */
public class EventUpdate extends CancellableEvent {

    public EventUpdate(final EventState state) {
        super(state);
    }
}
