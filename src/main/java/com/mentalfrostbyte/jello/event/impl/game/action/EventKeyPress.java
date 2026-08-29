package com.mentalfrostbyte.jello.event.impl.game.action;

import com.mentalfrostbyte.jello.event.CancellableEvent;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Raw keyboard or mouse button input, fired before vanilla dispatches it to key mappings. Cancelling
 * stops vanilla from seeing the input at all, which is how a bound module swallows its own key.
 *
 * <p>Only fired while no screen and no overlay are open, so a listener cannot eat the keystrokes of
 * someone typing in chat or clicking through a menu. Interface code that wants input while it is on
 * screen handles it as a {@code Screen} instead.</p>
 *
 * <p>The key is an {@link InputConstants.Key} rather than a bare int so keyboard and mouse buttons
 * cannot be confused with each other - GLFW numbers them from the same range.</p>
 */
public class EventKeyPress extends CancellableEvent {

    private final InputConstants.Key key;
    private final boolean pressed;

    public EventKeyPress(final InputConstants.Key key, final boolean pressed) {
        this.key = key;
        this.pressed = pressed;
    }

    public InputConstants.Key getKey() {
        return this.key;
    }

    /** GLFW code of the key or mouse button; only unique together with {@link #getKeyType()}. */
    public int getKeyCode() {
        return this.key.getValue();
    }

    public InputConstants.Type getKeyType() {
        return this.key.getType();
    }

    /** True for a press and for GLFW's auto-repeat, false for a release. */
    public boolean isPressed() {
        return this.pressed;
    }
}
