package com.mentalfrostbyte.jello.module.impl.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.event.impl.game.EventLoadWorld;
import org.junit.jupiter.api.Test;

class TargetHudTest {
    @Test
    void aRecentAttackExpiresAtTheConfiguredMonotonicDeadline() {
        long attack = 10_000_000_000L;
        assertTrue(TargetHud.withinHold(attack, attack, 3F));
        assertTrue(TargetHud.withinHold(attack, attack + 2_999_999_999L, 3F));
        assertFalse(TargetHud.withinHold(attack, attack + 3_000_000_000L, 3F));
        assertFalse(TargetHud.withinHold(attack, attack - 1, 3F));
        assertFalse(TargetHud.withinHold(attack, attack, 0F));
        assertFalse(TargetHud.withinHold(attack, attack, Float.NaN));
        assertFalse(TargetHud.withinHold(attack, attack, Float.POSITIVE_INFINITY));
    }

    @Test
    void nearbyNanoTimeInstantsStillWorkAcrossSignedCounterWrap() {
        long attack = Long.MAX_VALUE - 999_999_999L;
        assertTrue(TargetHud.withinHold(attack, attack + 1_000_000_000L, 3F));
        assertFalse(TargetHud.withinHold(attack, attack + 3_000_000_000L, 3F));
    }

    @Test
    void togglesAndWorldChangesInvalidateThePreviousHealthAnimationSession() {
        TargetHud module = new TargetHud();
        long beforeEnable = module.getSession();
        module.setEnabled(true);
        try {
            assertTrue(module.getSession() > beforeEnable);
            long beforeWorld = module.getSession();
            module.onLoadWorld(new EventLoadWorld(null));
            assertTrue(module.getSession() > beforeWorld);
        } finally {
            long beforeDisable = module.getSession();
            module.setEnabled(false);
            assertTrue(module.getSession() > beforeDisable);
        }
    }
}
