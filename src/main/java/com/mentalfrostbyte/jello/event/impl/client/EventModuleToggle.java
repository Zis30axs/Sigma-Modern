package com.mentalfrostbyte.jello.event.impl.client;

import com.mentalfrostbyte.jello.event.Event;
import com.mentalfrostbyte.jello.module.Module;

/**
 * A module was switched on or off, whatever did it - a keybind, the interface, a command, another module.
 *
 * <p>This is how everything that wants to react to a module's state stays out of the module layer: the
 * on-screen module list, a toggle sound, a notification all subscribe here instead of the base class
 * reaching out to them.</p>
 */
public class EventModuleToggle extends Event {

    private final Module module;

    public EventModuleToggle(final Module module) {
        this.module = module;
    }

    public Module getModule() {
        return this.module;
    }

    public boolean isEnabled() {
        return this.module.isEnabled();
    }
}
