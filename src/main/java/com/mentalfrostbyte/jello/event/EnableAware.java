package com.mentalfrostbyte.jello.event;

/**
 * Implemented by a subscriber that can be switched off while still holding a subscription, so
 * {@link EventBus} can stop delivering to it without needing to know what "off" means. See
 * {@link EventBus#call(Event)} for the stale-dispatch-snapshot race this exists to close.
 */
public interface EnableAware {

    boolean isEnabled();
}
