package com.mentalfrostbyte.jello.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.setting.NumberSetting;
import com.mentalfrostbyte.jello.setting.KeySetting;
import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.Keybind;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

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

    private static KeySetting shortcut() {
        return new KeySetting("Press Key", "test", KeySetting.Chord.of(
                InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F), GLFW.GLFW_MOD_ALT));
    }

    @Test
    void shortcutCaptureWaitsForTheMainKeyAndCommitsTheCombination() {
        KeySetting key = shortcut();
        this.interactions.handleSettingClick(key, 0, 0, 100);
        assertTrue(this.interactions.isBinding(key));
        assertEquals("Press a key...", this.interactions.displayValue(key));
        assertTrue(this.interactions.keyPressed(new KeyEvent(GLFW.GLFW_KEY_LEFT_CONTROL, 0, GLFW.GLFW_MOD_CONTROL)));
        assertTrue(this.interactions.isBinding(key));
        assertTrue(this.interactions.charTyped(new CharacterEvent('x')));
        assertTrue(this.interactions.keyPressed(new KeyEvent(GLFW.GLFW_KEY_G, 0, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT)));
        assertFalse(this.interactions.isBinding(key));
        assertEquals("Ctrl + Shift + G", this.interactions.displayValue(key));
        assertTrue(key.matches(new KeyEvent(GLFW.GLFW_KEY_G, 0, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT)));
    }

    @Test
    void escapeCancelsWhileDeleteAndBackspaceClearTheSettingOnly() {
        KeySetting key = shortcut();
        KeySetting.Chord original = key.get();
        this.interactions.startBind(key);
        assertTrue(this.interactions.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
        assertEquals(original, key.get());
        assertFalse(this.interactions.isCapturingKey());
        for (int clear : new int[]{GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE}) {
            key.reset();
            this.interactions.startBind(key);
            assertTrue(this.interactions.keyPressed(new KeyEvent(clear, 0, 0)));
            assertFalse(key.get().isBound());
            assertFalse(this.interactions.isCapturingKey());
        }
    }

    @Test
    void settingAndModuleCaptureAreMutuallyExclusiveAndMouseBindingStillWorks() {
        KeySetting key = shortcut();
        Module module = new Module(ModuleCategory.INTERFACE, "test", "") {};
        this.interactions.startBind(module);
        this.interactions.startBind(key);
        assertFalse(this.interactions.isBinding(module));
        this.interactions.keyPressed(new KeyEvent(GLFW.GLFW_KEY_G, 0, GLFW.GLFW_MOD_CONTROL));
        assertEquals(Keybind.UNBOUND, module.getKeybind());
        KeySetting.Chord captured = key.get();
        this.interactions.startBind(key);
        this.interactions.startBind(module);
        assertFalse(this.interactions.isBinding(key));
        assertTrue(this.interactions.mouseClickedBinding(new MouseButtonEvent(0, 0, new MouseButtonInfo(2, 0))));
        assertEquals(InputConstants.Type.MOUSE.getOrCreate(2), module.getKeybind().key());
        assertEquals(captured, key.get());
    }

    @Test
    void clickingElsewhereCancelsKeyboardOnlyCaptureWithoutSwallowingTheClick() {
        KeySetting key = shortcut();
        this.interactions.startBind(key);
        assertFalse(this.interactions.mouseClickedBinding(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0))));
        assertFalse(this.interactions.isCapturingKey());
        assertEquals(shortcut().get(), key.get());
        this.interactions.startBind(key);
        this.interactions.startEditing(this.size, "11.0");
        assertFalse(this.interactions.isCapturingKey());
        assertTrue(this.interactions.isEditing(this.size));
        this.interactions.startBind(key);
        assertFalse(this.interactions.isEditing(this.size));
    }
}
