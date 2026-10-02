package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.setting.BooleanSetting;
import org.junit.jupiter.api.Test;

class TargetTest {

    @Test
    void defaultsMatchTheOldKillAuraTargetFilters() {
        Target target = new Target();

        assertTrue(target.isEnabledByDefault());
        assertTrue(target.allows(Target.Kind.PLAYER, false));
        assertTrue(target.allows(Target.Kind.BOT, false), "FakePlayer used to count as a normal player");
        assertTrue(target.allows(Target.Kind.MOB, false));
        assertFalse(target.allows(Target.Kind.ANIMAL, false));
        assertFalse(target.allows(Target.Kind.PLAYER, true));
    }

    @Test
    void botsAreAnIndependentTargetCategory() {
        Target target = new Target();
        this.set(target, "Players", false);
        assertFalse(target.allows(Target.Kind.PLAYER, false));
        assertTrue(target.allows(Target.Kind.BOT, false), "Bots can still be selected without selecting all players");

        this.set(target, "Bots", false);
        assertFalse(target.allows(Target.Kind.BOT, false));
    }

    @Test
    void invisiblesAreAGlobalGateOverTheSelectedCategory() {
        Target target = new Target();
        assertFalse(target.allows(Target.Kind.MOB, true));
        this.set(target, "Invisibles", true);
        assertTrue(target.allows(Target.Kind.MOB, true));

        this.set(target, "Mobs", false);
        assertFalse(target.allows(Target.Kind.MOB, true));
    }

    private void set(final Target target, final String name, final boolean value) {
        ((BooleanSetting) target.setting(name).orElseThrow()).set(value);
    }
}
