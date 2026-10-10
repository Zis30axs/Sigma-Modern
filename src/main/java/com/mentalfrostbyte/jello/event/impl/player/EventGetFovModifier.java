package com.mentalfrostbyte.jello.event.impl.player;

import com.mentalfrostbyte.jello.event.Event;

/** The local player's field-of-view multiplier, after vanilla computes movement and item effects. */
public final class EventGetFovModifier extends Event {

    private float fovModifier;

    public EventGetFovModifier(final float fovModifier) {
        this.fovModifier = fovModifier;
    }

    public float getFovModifier() {
        return this.fovModifier;
    }

    public void setFovModifier(final float fovModifier) {
        this.fovModifier = fovModifier;
    }
}
