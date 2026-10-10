package com.mentalfrostbyte.jello.setting;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class KeySettingTest {
    private static KeySetting shortcut() {
        return new KeySetting("Press Key", "test", new KeySetting.Chord(
                InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F), GLFW.GLFW_MOD_ALT));
    }

    @Test void persistsStableKeyNamesAndExactModifiers() {
        KeySetting setting = shortcut();
        assertEquals("key.keyboard.f", setting.toJson().getAsJsonObject().get("key").getAsString());
        assertEquals(GLFW.GLFW_MOD_ALT, setting.toJson().getAsJsonObject().get("modifiers").getAsInt());
        KeySetting copy = new KeySetting("Copy", "", KeySetting.Chord.UNBOUND);
        assertTrue(copy.fromJson(setting.toJson()));
        assertEquals(setting.get(), copy.get());
        assertEquals("Alt + F", copy.displayLabel());
        setting.set(KeySetting.Chord.UNBOUND);
        assertTrue(copy.fromJson(setting.toJson()));
        assertFalse(copy.get().isBound());
        assertEquals("None", copy.displayLabel());
    }

    @Test void malformedConfigLeavesTheCurrentShortcutAlone() {
        KeySetting setting = shortcut();
        KeySetting.Chord original = setting.get();
        for (String json : List.of("null", "[]", "7", "{}", "{\"key\":false,\"modifiers\":4}",
                "{\"key\":\"key.keyboard.f\"}", "{\"key\":\"key.keyboard.f\",\"modifiers\":{}}",
                "{\"key\":\"key.keyboard.f\",\"modifiers\":1.5}", "{\"key\":\"key.keyboard.f\",\"modifiers\":32}",
                "{\"key\":\"key.keyboard.f\",\"modifiers\":1e100}", "{\"key\":\"key.keyboard\",\"modifiers\":0}",
                "{\"key\":\"key.keyboard.999999\",\"modifiers\":0}", "{\"key\":\"key.mouse.left\",\"modifiers\":0}",
                "{\"key\":\"key.keyboard.unknown\",\"modifiers\":4}", "{\"key\":\"key.keyboard.left.alt\",\"modifiers\":0}",
                "{\"key\":\"key.keyboard.not-a-key\",\"modifiers\":0}")) {
            assertFalse(assertDoesNotThrow(() -> setting.fromJson(JsonParser.parseString(json))), json);
            assertEquals(original, setting.get(), json);
        }
        assertFalse(setting.fromJson(JsonNull.INSTANCE));
        assertFalse(setting.fromJson(null));
    }

    @Test void modifiersMatchExactlyWhileCapsAndNumLockDoNotMatter() {
        KeySetting setting = shortcut();
        assertTrue(setting.matches(new KeyEvent(GLFW.GLFW_KEY_F, 0, GLFW.GLFW_MOD_ALT)));
        assertTrue(setting.matches(new KeyEvent(GLFW.GLFW_KEY_F, 0,
                GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_CAPS_LOCK | GLFW.GLFW_MOD_NUM_LOCK)));
        assertFalse(setting.matches(new KeyEvent(GLFW.GLFW_KEY_F, 0, 0)));
        assertFalse(setting.matches(new KeyEvent(GLFW.GLFW_KEY_F, 0, GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_CONTROL)));
        assertFalse(setting.matches(new KeyEvent(GLFW.GLFW_KEY_G, 0, GLFW.GLFW_MOD_ALT)));
        setting.set(KeySetting.Chord.UNBOUND);
        assertFalse(setting.matches(new KeyEvent(GLFW.GLFW_KEY_UNKNOWN, 0, 0)));
    }

    @Test void modifierOnlyPressesCannotCompleteCapture() {
        for (int modifier = GLFW.GLFW_KEY_LEFT_SHIFT; modifier <= GLFW.GLFW_KEY_RIGHT_SUPER; modifier++) {
            assertTrue(KeySetting.capture(new KeyEvent(modifier, 0, 15)).isEmpty());
        }
        assertTrue(KeySetting.capture(new KeyEvent(GLFW.GLFW_KEY_UNKNOWN, 0, 0)).isEmpty());
        KeySetting.Chord captured = KeySetting.capture(new KeyEvent(GLFW.GLFW_KEY_F, 0,
                GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_CAPS_LOCK)).orElseThrow();
        assertEquals(shortcut().get(), captured);
    }

    @Test void invalidProgrammaticChordsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> new KeySetting.Chord(InputConstants.Type.MOUSE.getOrCreate(0), 0));
        assertThrows(IllegalArgumentException.class, () -> new KeySetting.Chord(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_ALT), 0));
        assertThrows(IllegalArgumentException.class, () -> new KeySetting.Chord(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F), 128));
        assertThrows(IllegalArgumentException.class, () -> new KeySetting.Chord(InputConstants.UNKNOWN, GLFW.GLFW_MOD_ALT));
    }

    @Test void scanCodeKeysRoundTripWithoutCollidingWithSymbolKeys() {
        KeySetting setting = shortcut();
        setting.set(KeySetting.capture(new KeyEvent(GLFW.GLFW_KEY_UNKNOWN, 200, GLFW.GLFW_MOD_CONTROL)).orElseThrow());
        KeySetting restored = shortcut();
        assertTrue(restored.fromJson(setting.toJson()));
        assertTrue(restored.matches(new KeyEvent(GLFW.GLFW_KEY_UNKNOWN, 200, GLFW.GLFW_MOD_CONTROL)));
        assertFalse(restored.matches(new KeyEvent(200, 200, GLFW.GLFW_MOD_CONTROL)));
    }
}
