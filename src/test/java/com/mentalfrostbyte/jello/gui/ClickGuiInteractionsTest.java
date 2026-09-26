package com.mentalfrostbyte.jello.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;

/** Typing a number's value, as the ClickGUI's value pill does. */
class ClickGuiInteractionsTest {

    private final ClickGuiInteractions interactions = new ClickGuiInteractions();

    private final NumberSetting size = new NumberSetting("Font Size", "test", 11F, 8F, 16F, 0.5F);

    private void type(final String text) {
        this.interactions.startEditing(this.size, "");
        text.codePoints().forEach(cp -> this.interactions.charTyped(new CharacterEvent(cp)));
    }

    private void press(final int key) {
        this.interactions.keyPressed(new KeyEvent(key, 0, 0));
    }

    @Test
    void enterSetsTheTypedValue() {
        this.interactions.startEditing(this.size, "11.0");
        assertTrue(this.interactions.isEditing(this.size));
        assertEquals("11.0", this.interactions.displayValue(this.size), "the pill shows what is being typed");
        type("13,5");
        press(InputConstants.KEY_RETURN);
        assertEquals(13.5F, this.size.get(), "a decimal comma works too");
        assertFalse(this.interactions.isEditing(this.size));
    }

    @Test
    void aTypedValueIsClampedAndNonsenseIsIgnored() {
        type("99");
        press(InputConstants.KEY_RETURN);
        assertEquals(16F, this.size.get());

        type("big");
        press(InputConstants.KEY_RETURN);
        assertEquals(16F, this.size.get());

        type("9");
        press(InputConstants.KEY_ESCAPE);
        assertEquals(16F, this.size.get(), "Esc leaves it as it was");
    }
}
