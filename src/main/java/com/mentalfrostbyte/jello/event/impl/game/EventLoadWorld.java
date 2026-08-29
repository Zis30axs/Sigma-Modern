package com.mentalfrostbyte.jello.event.impl.game;

import com.mentalfrostbyte.jello.event.Event;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Fired when the client attaches a new level, i.e. on joining a world or crossing a dimension.
 * Modules use it to drop per-world state.
 */
public class EventLoadWorld extends Event {

    private final ClientLevel level;

    public EventLoadWorld(final ClientLevel level) {
        this.level = level;
    }

    public ClientLevel getLevel() {
        return this.level;
    }
}
