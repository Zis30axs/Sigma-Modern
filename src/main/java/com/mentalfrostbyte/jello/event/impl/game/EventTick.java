package com.mentalfrostbyte.jello.event.impl.game;

import com.mentalfrostbyte.jello.event.Event;
import com.mentalfrostbyte.jello.event.EventState;

/**
 * Fired at the head ({@link EventState#PRE}) and tail ({@link EventState#POST}) of a client tick,
 * i.e. 20 times a second while the game is not paused.
 *
 * <p>PRE runs before keybinds are polled and before the level ticks, which is where per-tick module
 * setup belongs; POST runs after the keyboard handler, for end-of-tick cleanup.</p>
 */
public class EventTick extends Event {

    public EventTick(final EventState state) {
        super(state);
    }
}
