package com.mentalfrostbyte.jello.gui.modern;

/**
 * Marks a Modern screen whose glass panels are designed over a strong backdrop blur. While one is open,
 * {@code GameRenderer.extractOptions} forces the blur to its maximum radius rather than leaving it to the
 * user's "menu background blurriness" slider - the same treatment the ClickGUI has always had.
 */
public interface ModernBlurredBackdrop {
}
