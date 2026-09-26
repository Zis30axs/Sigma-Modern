package com.mentalfrostbyte.jello.module.impl.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class ModuleAntiCheatTest {

    @Test
    void startsOffWithEveryCheckOnAtBalancedSensitivity() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        assertFalse(module.isEnabled());
        assertEquals(ModuleCategory.MISC, module.getCategory());
        assertEquals("AntiCheat", module.getName());
        for (String check : new String[] {"Speed", "Flight", "GroundSpoof", "NoSlow", "Notify", "NameTag"}) {
            assertTrue(((BooleanSetting) module.setting(check).orElseThrow()).get(), check + " should default on");
        }

        @SuppressWarnings("unchecked")
        EnumSetting<ModuleAntiCheat.Sensitivity> sensitivity =
                (EnumSetting<ModuleAntiCheat.Sensitivity>) module.setting("Sensitivity").orElseThrow();
        assertEquals(ModuleAntiCheat.Sensitivity.BALANCED, sensitivity.get());
    }

    @Test
    void switchingOnAndOffClearsWhatWasFlagged() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        UUID id = UUID.randomUUID();
        module.suspects().update(id, "Steve", "Speed", 5.0);
        assertEquals(5.0, module.suspects().totalLevel(id), 1.0E-9);

        module.setEnabled(true);
        assertTrue(module.isEnabled());
        assertEquals(0.0, module.suspects().totalLevel(id), 1.0E-9, "enabling starts fresh");

        module.suspects().update(id, "Steve", "Speed", 5.0);
        module.setEnabled(false);
        assertFalse(module.isEnabled());
        assertEquals(0.0, module.suspects().totalLevel(id), 1.0E-9, "disabling forgets the flags");
    }

    @Test
    void forgettingASuspectRemovesThemFromTheList() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        module.suspects().update(a, "A", "Speed", 4.0);
        module.suspects().update(b, "B", "Flight", 4.0);

        module.forget(a);
        assertEquals(0.0, module.suspects().totalLevel(a), 1.0E-9);
        assertEquals(4.0, module.suspects().totalLevel(b), 1.0E-9);

        module.forgetAll();
        assertTrue(module.suspects().ranked().isEmpty());
    }

    @Test
    void theNameTagIsLeftAloneUntilTheAlertLevelIsReached() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        UUID id = UUID.randomUUID();
        Component name = Component.literal("Steve");

        assertSame(name, module.tagged(id, name));

        module.suspects().update(id, "Steve", "Speed", 2.0);
        assertSame(name, module.tagged(id, name), "2 is under the default alert level of 3");

        module.suspects().update(id, "Steve", "Speed", 4.0);
        String tagged = module.tagged(id, name).getString();
        assertTrue(tagged.startsWith("Steve"), tagged);
        assertTrue(tagged.contains("VL 4"), tagged);
    }

    @Test
    void theNameTagCanBeSwitchedOff() {
        ModuleAntiCheat module = new ModuleAntiCheat();
        ((BooleanSetting) module.setting("NameTag").orElseThrow()).set(false);
        UUID id = UUID.randomUUID();
        module.suspects().update(id, "Steve", "Speed", 9.0);
        Component name = Component.literal("Steve");
        assertSame(name, module.tagged(id, name));
    }
}
