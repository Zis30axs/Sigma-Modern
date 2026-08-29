package com.mentalfrostbyte.jello.event.impl.player;

import com.mentalfrostbyte.jello.event.Event;
import com.mentalfrostbyte.jello.event.EventState;

/**
 * The local player's living entity step, fired at the head ({@link EventState#PRE}) and tail
 * ({@link EventState#POST}).
 *
 * <p>Unlike {@link EventUpdate} this sits inside the physics step: PRE runs before input is turned
 * into movement flags (sneak, sprint, jump, flight toggles), POST after the player has actually
 * moved. Modules that need to influence movement without desyncing the position packet work here.</p>
 */
public class EventLivingUpdate extends Event {

    public EventLivingUpdate(final EventState state) {
        super(state);
    }
}
