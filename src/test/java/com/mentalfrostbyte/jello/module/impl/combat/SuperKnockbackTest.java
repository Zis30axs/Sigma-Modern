package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.setting.BooleanSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import org.junit.jupiter.api.Test;

class SuperKnockbackTest {

    @Test
    void onlyForwardIsOfferedWhileOnlyOnMoveIsOn() {
        SuperKnockback superKnockback = new SuperKnockback();
        assertEquals(ModuleCategory.COMBAT, superKnockback.getCategory());
        Setting<?> onlyForward = superKnockback.setting("Only Forward").orElseThrow();
        assertTrue(onlyForward.isVisible());

        ((BooleanSetting) superKnockback.setting("Only On Move").orElseThrow()).set(false);
        assertFalse(onlyForward.isVisible());
    }
}
