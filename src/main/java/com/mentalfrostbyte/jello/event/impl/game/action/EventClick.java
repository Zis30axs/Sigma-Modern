package com.mentalfrostbyte.jello.event.impl.game.action;

import com.mentalfrostbyte.jello.event.CancellableEvent;

/**
 * Fired when the client acts on a mouse button: attack, use item, or pick block. Cancelling drops
 * the action, which is how modules take over attacking or placing.
 *
 * <p>This is the interaction, not the raw input - see {@link EventKeyPress} for the button itself.</p>
 */
public class EventClick extends CancellableEvent {

    private final Button button;

    public EventClick(final Button button) {
        this.button = button;
    }

    public Button getButton() {
        return this.button;
    }

    public enum Button {
        LEFT,
        RIGHT,
        MIDDLE
    }
}
