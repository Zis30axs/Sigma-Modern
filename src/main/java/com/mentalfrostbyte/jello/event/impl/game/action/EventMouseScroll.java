package com.mentalfrostbyte.jello.event.impl.game.action;

import com.mentalfrostbyte.jello.event.CancellableEvent;

/**
 * Mouse wheel movement, fired before vanilla applies sensitivity and changes the held slot.
 * Cancelling keeps the scroll from reaching the game.
 *
 * <p>Offsets are raw GLFW values - positive vertical is scroll up - and are reported before the
 * discrete-scroll and sensitivity options are applied. Like {@link EventKeyPress}, only fired while no
 * screen and no overlay are open.</p>
 */
public class EventMouseScroll extends CancellableEvent {

    private final double horizontal;
    private final double vertical;

    public EventMouseScroll(final double horizontal, final double vertical) {
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    public double getHorizontal() {
        return this.horizontal;
    }

    public double getVertical() {
        return this.vertical;
    }
}
