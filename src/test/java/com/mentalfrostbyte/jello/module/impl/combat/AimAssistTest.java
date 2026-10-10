package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import org.junit.jupiter.api.Test;

class AimAssistTest {
    @Test
    void startsWithClickGatedAssistAndNoAutomaticEnable() {
        AimAssist module = new AimAssist();

        assertEquals(ModuleCategory.COMBAT, module.getCategory());
        assertFalse(module.isEnabledByDefault());
        assertFalse(module.isEnabled());
        assertEquals(AimAssist.Mode.ASSIST, mode(module).get());
        assertTrue(((BooleanSetting) module.setting("Only On Click").orElseThrow()).get());
    }

    @Test
    void lockHidesOptionsThatDoNotApplyToItsMandatoryClickGate() {
        AimAssist module = new AimAssist();
        mode(module).set(AimAssist.Mode.LOCK);

        assertFalse(visible(module, "Only On Click"));
        assertFalse(visible(module, "Strength"));
        assertFalse(visible(module, "Randomness"));
        assertFalse(visible(module, "Training"));
        assertTrue(visible(module, "Range"));
        assertTrue(visible(module, "FOV"));
        assertTrue(visible(module, "Turn Speed"));
    }

    @Test
    void trainingAndRandomnessAreOnlyOfferedForLoRA() {
        AimAssist module = new AimAssist();
        assertTrue(visible(module, "Strength"));
        assertTrue(visible(module, "Only On Click"));
        assertFalse(visible(module, "Training"));
        assertFalse(visible(module, "Randomness"));

        mode(module).set(AimAssist.Mode.LORA);
        assertTrue(visible(module, "Strength"));
        assertTrue(visible(module, "Only On Click"));
        assertTrue(visible(module, "Training"));
        assertTrue(visible(module, "Randomness"));
        assertSame(module.setting("Training").orElseThrow(), module.getTrainingSetting(),
                "Both settings panels must open training from the registered setting");

        mode(module).set(AimAssist.Mode.ASSIST);
        assertFalse(visible(module, "Training"));
        assertFalse(visible(module, "Randomness"));
    }

    @Test
    void readableModeLabelsKeepStableConfigurationNames() {
        AimAssist module = new AimAssist();
        mode(module).set(AimAssist.Mode.LORA);

        assertEquals("Lock", EnumSetting.label(AimAssist.Mode.LOCK));
        assertEquals("Assist", EnumSetting.label(AimAssist.Mode.ASSIST));
        assertEquals("LoRA", EnumSetting.label(AimAssist.Mode.LORA));
        assertEquals("LORA", mode(module).toJson().getAsString());
    }

    private static boolean visible(AimAssist module, String name) {
        return module.setting(name).orElseThrow().isVisible();
    }

    @SuppressWarnings("unchecked")
    private static EnumSetting<AimAssist.Mode> mode(AimAssist module) {
        return (EnumSetting<AimAssist.Mode>) module.setting("Mode").orElseThrow();
    }
}
