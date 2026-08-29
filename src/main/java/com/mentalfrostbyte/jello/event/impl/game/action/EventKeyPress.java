package com.mentalfrostbyte.jello.event.impl.game.action;

import com.mentalfrostbyte.jello.event.CancellableEvent;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * A key or mouse button changed state while no screen was open.
 *
 * <p>Keyboard keys and mouse buttons both arrive here, identified by {@link InputConstants.Key} rather
 * than a bare code, so the two namespaces cannot collide and a binding can name either one.</p>
 *
 * <p>{@link Action#REPEAT} is GLFW's auto-repeat, sent while a key is held down. Anything that reacts
 * to a key being pressed - toggling a module, for one - must look for {@link Action#PRESS} alone, or it
 * fires again on every repeat.</p>
 */
public class EventKeyPress extends CancellableEvent {

    private final InputConstants.Key key;

    private final Action action;

    public EventKeyPress(final InputConstants.Key key, final Action action) {
        this.key = key;
        this.action = action;
    }

    public InputConstants.Key getKey() {
        return this.key;
    }

    public int getKeyCode() {
        return this.key.getValue();
    }

    public InputConstants.Type getKeyType() {
        return this.key.getType();
    }

    public Action getAction() {
        return this.action;
    }

    /** True for {@link Action#PRESS} and {@link Action#REPEAT}: the key is physically down. */
    public boolean isDown() {
        return this.action != Action.RELEASE;
    }

    /** What happened to the key. Mouse buttons only ever report {@link #PRESS} and {@link #RELEASE}. */
    public enum Action {
        PRESS,
        REPEAT,
        RELEASE;

        /** Maps GLFW's action code: 0 release, 1 press, 2 auto-repeat. */
        public static Action fromGlfw(final int action) {
            return switch (action) {
                case 0 -> RELEASE;
                case 2 -> REPEAT;
                default -> PRESS;
            };
        }
    }
}
