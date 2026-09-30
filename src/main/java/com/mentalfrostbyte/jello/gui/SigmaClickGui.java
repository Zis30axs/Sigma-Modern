package com.mentalfrostbyte.jello.gui;

/**
 * Implemented by every Sigma ClickGUI screen, regardless of presentation mode.
 *
 * <p>This lets the global hotkey close whichever Sigma GUI is currently open without coupling the input
 * handler to one specific screen implementation.</p>
 */
public interface SigmaClickGui {

    /**
     * Asks the screen to close itself the way it likes - Jello plays its panels away first. Returns whether it took
     * over: {@code true} means the screen will call {@code ClickGuiHandler.close()} itself when it is done, and the
     * caller must not.
     */
    default boolean beginClose() {
        return false;
    }
}
