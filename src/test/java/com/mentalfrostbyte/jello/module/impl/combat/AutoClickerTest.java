package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.NumberSetting;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutoClickerTest {
    @Test
    void startsDisabledInAutoMode() {
        AutoClicker module = new AutoClicker();

        assertEquals(ModuleCategory.COMBAT, module.getCategory());
        assertFalse(module.isEnabledByDefault());
        assertFalse(module.isEnabled());
        assertEquals(AutoClicker.Mode.AUTO, mode(module).get());
    }

    @Test
    void changingModeOnlyShowsItsOwnControlsAndPreservesValues() {
        AutoClicker module = new AutoClicker();
        List<String> autoControls = List.of("Min CPS", "Max CPS", "Zitter", "Jitter", "MS", "Random");
        List<String> helperControls = List.of("Extra Clicks", "Helper Delay");
        number(module, "Min CPS").set(10.0F);

        for (String name : autoControls) {
            assertTrue(module.setting(name).orElseThrow().isVisible(), name);
        }
        for (String name : helperControls) {
            assertFalse(module.setting(name).orElseThrow().isVisible(), name);
        }

        mode(module).set(AutoClicker.Mode.HELPER);
        for (String name : autoControls) {
            assertFalse(module.setting(name).orElseThrow().isVisible(), name);
        }
        for (String name : helperControls) {
            assertTrue(module.setting(name).orElseThrow().isVisible(), name);
        }
        number(module, "Extra Clicks").set(3.0F);

        mode(module).set(AutoClicker.Mode.AUTO);
        assertEquals(10.0F, number(module, "Min CPS").get());
        assertEquals(3.0F, number(module, "Extra Clicks").get());
        assertTrue(module.setting("Min CPS").orElseThrow().isVisible());
        assertFalse(module.setting("Extra Clicks").orElseThrow().isVisible());
    }

    @Test
    void readableModeNamesKeepStableConfigValues() {
        AutoClicker module = new AutoClicker();

        assertEquals("Auto", EnumSetting.label(AutoClicker.Mode.AUTO));
        assertEquals("Helper", EnumSetting.label(AutoClicker.Mode.HELPER));
        assertEquals("AUTO", mode(module).toJson().getAsString());
        assertTrue(mode(module).fromJson(new JsonPrimitive("HELPER")));
        assertEquals(AutoClicker.Mode.HELPER, mode(module).get());
        assertFalse(mode(module).fromJson(new JsonPrimitive("missing-mode")));
        assertEquals(AutoClicker.Mode.HELPER, mode(module).get());
    }

    @Test
    void editedConfigCannotEscapeTheClickRateAndBurstLimits() {
        AutoClicker module = new AutoClicker();

        assertClamped(module, "Min CPS", 1.0F, 30.0F);
        assertClamped(module, "Max CPS", 1.0F, 30.0F);
        assertClamped(module, "Zitter", 0.0F, 5.0F);
        assertClamped(module, "Jitter", 0.0F, 30.0F);
        assertClamped(module, "MS", 50.0F, 2000.0F);
        assertClamped(module, "Random", 0.0F, 100.0F);
        assertClamped(module, "Extra Clicks", 1.0F, 5.0F);
        assertClamped(module, "Helper Delay", 5.0F, 250.0F);
    }

    private static void assertClamped(AutoClicker module, String name, float lower, float upper) {
        NumberSetting setting = number(module, name);
        assertTrue(setting.fromJson(new JsonPrimitive(-1000.0F)));
        assertEquals(lower, setting.get(), name);
        assertTrue(setting.fromJson(new JsonPrimitive(10000.0F)));
        assertEquals(upper, setting.get(), name);
        assertFalse(setting.fromJson(new JsonPrimitive(Float.NaN)));
        assertEquals(upper, setting.get(), name);
    }

    private static NumberSetting number(AutoClicker module, String name) {
        return (NumberSetting) module.setting(name).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static EnumSetting<AutoClicker.Mode> mode(AutoClicker module) {
        return (EnumSetting<AutoClicker.Mode>) module.setting("Mode").orElseThrow();
    }
}
