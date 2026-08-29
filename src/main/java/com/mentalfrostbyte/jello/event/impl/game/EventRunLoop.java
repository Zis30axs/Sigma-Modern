package com.mentalfrostbyte.jello.event.impl.game;

import com.mentalfrostbyte.jello.event.Event;

/**
 * Fired once per rendered frame, before the frame's client ticks run.
 *
 * <p>Use this for anything that should advance at framerate rather than tickrate - animations,
 * interpolation, timers driving the interface - and {@link EventTick} for game logic.</p>
 */
public class EventRunLoop extends Event {
}
