package com.mentalfrostbyte.jello.event;

/**
 * Base type for everything dispatched through the {@link EventBus}.
 *
 * <p>Every event carries an {@link EventState} so hooks that wrap a vanilla method can fire one
 * event instance twice instead of declaring a separate "pre" and "post" event, or smuggling an
 * ad-hoc {@code boolean pre} field into each subclass.</p>
 */
public abstract class Event {

    private EventState state;

    protected Event() {
        this(EventState.PRE);
    }

    protected Event(final EventState state) {
        this.state = state;
    }

    public final EventState getState() {
        return this.state;
    }

    public final void setState(final EventState state) {
        this.state = state;
    }

    public final boolean isPre() {
        return this.state == EventState.PRE;
    }

    public final boolean isPost() {
        return this.state == EventState.POST;
    }
}
