package com.mentalfrostbyte.jello.module.impl.gui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mentalfrostbyte.jello.config.ModuleConfig;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import org.junit.jupiter.api.Test;

class KeyboardTest {
    @Test void oldJelloKeyStrokesPreferenceDoesNotHideTheModernKeyboard() {
        ModuleManager modules = new ModuleManager();
        Keyboard modern = new Keyboard();
        KeyStrokes legacy = new KeyStrokes();
        modules.register(modern);
        modules.register(legacy);
        try {
            ModuleConfig.read(JsonParser.parseString("""
                    {"modules":{"KeyStrokes":{"enabled":false}}}
                    """).getAsJsonObject(), modules);
            assertTrue(modern.isEnabled(), "an older file keeps Modern's previously resident keyboard visible");
            assertFalse(legacy.isEnabled(), "the old Jello preference stays independent");
            assertTrue(modern.showsMouseButtons());
            assertTrue(modern.showsCps());
        } finally {
            modern.setEnabled(false);
            legacy.setEnabled(false);
        }
    }

    @Test void hiddenCpsPreferenceAndDisplayScaleSurviveConfigReload() {
        ModuleManager modules = new ModuleManager();
        Keyboard original = new Keyboard();
        modules.register(original);
        ModuleConfig.read(JsonParser.parseString("""
                {"modules":{"Keyboard":{"enabled":false,"settings":{
                  "Mouse Buttons":false,"CPS":true,"Scale":1.25,"Background":0.45
                }}}}
                """).getAsJsonObject(), modules);
        assertFalse(original.showsCps());
        assertFalse(original.setting("CPS").orElseThrow().isVisible());
        JsonObject saved = new JsonObject();
        ModuleConfig.write(saved, modules);
        Keyboard restored = new Keyboard();
        ModuleManager restoredModules = new ModuleManager();
        restoredModules.register(restored);
        ModuleConfig.read(JsonParser.parseString(saved.toString()).getAsJsonObject(), restoredModules);
        assertFalse(restored.isEnabled());
        assertEquals(1.25F, restored.getScale());
        assertEquals(0.45F, restored.getBackground());
        assertFalse(restored.showsCps());
        ((BooleanSetting)restored.setting("Mouse Buttons").orElseThrow()).set(true);
        assertTrue(restored.showsCps(), "the hidden CPS preference should return when its row is restored");
    }
}
