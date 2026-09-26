package com.mentalfrostbyte.jello.module.impl.gui;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;

/**
 * Sets the chat in Anthropic Serif on rounded ice glass: an input bar that unfurls when the chat opens, one
 * panel for the messages instead of a black strip per line, and glass cards for command suggestions.
 *
 * <p>The drawing lives in {@code gui.modern.ModernChat}, reached from marked hooks in the vanilla chat classes
 * that ask {@code Modules.enabled} for this module; vanilla keeps deciding what the chat says and does. This
 * class only holds the switch and the numbers, and works under any presentation. Nothing needs setting up or
 * tearing down: switched off, the next frame is vanilla's chat again, re-wrapped in vanilla's font.</p>
 */
public class ModernChat extends Module {

    /** The size the rest of SigmaModern's text is drawn at. */
    public static final float DEFAULT_FONT_SIZE = 11.0F;

    private final NumberSetting fontSize = this.register(new NumberSetting(
            "Font Size", "Chat text size in pixels. Lines, the input bar and wrapping follow it; the game's own"
            + " font is about 9.", DEFAULT_FONT_SIZE, 8.0F, 16.0F, 0.5F));

    private final NumberSetting background = this.register(new NumberSetting(
            "Background", "How solid the glass behind the messages is. Replaces the game's text background"
            + " opacity for this chat.", 0.5F, 0.0F, 1.0F, 0.05F));

    private final NumberSetting cornerRadius = this.register(new NumberSetting(
            "Corner Radius", "How round the panel, the input bar and the suggestion cards are. 0 is square.",
            10.0F, 0.0F, 12.0F, 1.0F));

    private final BooleanSetting animations = this.register(new BooleanSetting(
            "Animations", "Unfurl the input bar and grow the panel when the chat opens, fold them away when it"
            + " closes, and slide new lines in.", true));

    private final NumberSetting animationSpeed = this.register(new NumberSetting(
            "Animation Speed", "How fast those animations run. 1 is the default pace.", 1.0F, 0.5F, 2.0F, 0.1F));

    private final BooleanSetting textShadow = this.register(new BooleanSetting(
            "Text Shadow", "A drop shadow under the messages, for a see-through background.", false));

    public ModernChat() {
        super(ModuleCategory.INTERFACE, "ModernChat", "Sets the chat in Anthropic Serif on rounded glass with an animated input bar");
        this.animationSpeed.visibleWhen(this.animations::get);
    }

    public float getFontSize() {
        return this.fontSize.get();
    }

    public float getBackground() {
        return this.background.get();
    }

    public int getCornerRadius() {
        return this.cornerRadius.getInt();
    }

    public boolean isAnimated() {
        return this.animations.get();
    }

    public float getAnimationSpeed() {
        return this.animationSpeed.get();
    }

    public boolean hasTextShadow() {
        return this.textShadow.get();
    }
}
