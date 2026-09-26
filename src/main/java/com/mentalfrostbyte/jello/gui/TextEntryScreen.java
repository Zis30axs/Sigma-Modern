package com.mentalfrostbyte.jello.gui;

/**
 * A screen with a Sigma text field of its own (e.g. SigmaModern's music search). While it reports typing, global
 * hotkeys that run ahead of the screen - the ClickGUI key - leave the key to the field, so Right Shift types a
 * capital instead of opening or closing a screen.
 */
public interface TextEntryScreen {
    boolean isTypingText();
}
