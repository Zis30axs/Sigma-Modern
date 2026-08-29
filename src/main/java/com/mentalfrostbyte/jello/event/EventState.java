package com.mentalfrostbyte.jello.event;

/**
 * Which side of a hook an event was fired from.
 *
 * <p>Vanilla hooks that wrap a method fire the same event twice: once with {@link #PRE} before the
 * vanilla body runs and once with {@link #POST} after it. Listeners that only care about one side
 * check {@link Event#isPre()} / {@link Event#isPost()}.</p>
 */
public enum EventState {
    PRE,
    POST
}
