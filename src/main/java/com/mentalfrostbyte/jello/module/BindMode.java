package com.mentalfrostbyte.jello.module;

/** How a module reacts to its key. */
public enum BindMode {

    /** Pressing the key flips the module on or off, and it stays that way. */
    TOGGLE,

    /** The module is on for exactly as long as the key is held. */
    HOLD
}
