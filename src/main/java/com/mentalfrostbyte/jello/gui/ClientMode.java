package com.mentalfrostbyte.jello.gui;

/**
 * The client's presentation mode.
 *
 * <p>This is a UI/presentation concept only. It never changes which modules are registered, whether a
 * module is enabled, or what a module's canonical metadata is.</p>
 */
public enum ClientMode {
    JELLO,
    CLASSIC,
    NO_ADDONS
}
