package com.mentalfrostbyte.jello.event;

/**
 * Dispatch order for {@link EventTarget} listeners of the same event type: {@link #HIGHEST} runs
 * first, {@link #LOWEST} last.
 */
public enum EventPriority {
    LOWEST,
    LOW,
    NORMAL,
    HIGH,
    HIGHEST
}
