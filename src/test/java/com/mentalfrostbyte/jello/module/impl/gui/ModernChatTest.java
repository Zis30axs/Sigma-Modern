package com.mentalfrostbyte.jello.module.impl.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import org.junit.jupiter.api.Test;

class ModernChatTest {

    /** Switched on untouched, the module must look exactly like the chat that was reviewed before it became one. */
    @Test
    void defaultsReproduceTheReviewedLook() {
        ModernChat chat = new ModernChat();
        assertFalse(chat.isEnabled(), "A new module starts off");
        assertEquals(ModuleCategory.INTERFACE, chat.getCategory());
        assertEquals(ModernChat.DEFAULT_FONT_SIZE, chat.getFontSize());
        assertEquals(0.5F, chat.getBackground());
        assertEquals(10, chat.getCornerRadius());
        assertTrue(chat.isAnimated());
        assertEquals(1.0F, chat.getAnimationSpeed());
        assertFalse(chat.hasTextShadow());
    }

    @Test
    void animationSpeedOnlyShowsWhileAnimationsAreOn() {
        ModernChat chat = new ModernChat();
        var speed = chat.setting("Animation Speed").orElseThrow();
        var animations = (BooleanSetting) chat.setting("Animations").orElseThrow();
        assertTrue(speed.isVisible());
        animations.set(false);
        assertFalse(speed.isVisible());
    }
}
