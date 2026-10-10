package com.mentalfrostbyte.jello.module.impl.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DisablerTest {
    @Test
    void exposesOnlyTheFourOriginalGrimOptionsWithTheirOriginalDefaults() {
        Disabler module = new Disabler();

        assertEquals(Set.of("DisBadPacketA", "DisBadPacketF", "DisAimModulo360", "DisDuplicateRotPlace"),
                module.settings().stream().map(Setting::getName).collect(Collectors.toSet()));
        for (Setting<?> setting : module.settings()) {
            assertTrue(assertInstanceOf(BooleanSetting.class, setting).get(), setting.getName());
        }
    }

    @Test
    void packetRewritingRequiresExplicitlyEnablingTheWorldModule() {
        Disabler module = new Disabler();

        assertEquals("Disabler", module.getName());
        assertEquals(ModuleCategory.WORLD, module.getCategory());
        assertFalse(module.isEnabled());
        assertFalse(module.isEnabledByDefault());
    }
}
