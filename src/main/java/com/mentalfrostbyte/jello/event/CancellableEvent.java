package com.mentalfrostbyte.jello.event;

/**
 * An {@link Event} whose hook site honours cancellation: when a listener cancels it, the vanilla
 * body the hook wraps is skipped.
 *
 * <p>Cancelling does not stop the remaining listeners from being notified - every subscriber still
 * sees the event, and the last writer wins. Only the hook site reads the flag.</p>
 */
public abstract class CancellableEvent extends Event {

    private boolean cancelled;

    protected CancellableEvent() {
    }

    protected CancellableEvent(final EventState state) {
        super(state);
    }

    public final boolean isCancelled() {
        return this.cancelled;
    }

    public final void setCancelled(final boolean cancelled) {
        this.cancelled = cancelled;
    }

    /** Shorthand for {@code setCancelled(true)}. */
    public final void cancel() {
        this.cancelled = true;
    }
}
