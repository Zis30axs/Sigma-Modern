package com.mentalfrostbyte.jello.module.impl.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.entity.player.Input;
import org.junit.jupiter.api.Test;

class StuckTest {
    @Test
    void lastAutomaticBorrowerClosesTheModule() {
        Stuck.Holds holds = new Stuck.Holds();
        Object pearl = new Object();
        Object other = new Object();
        holds.acquire(pearl, 40, false);
        holds.acquire(other, 10, true);
        assertFalse(holds.release(pearl));
        assertTrue(holds.contains(other));
        assertTrue(holds.release(other));
    }

    @Test
    void manuallyEnabledModuleSurvivesBothReleaseAndExpiry() {
        Stuck.Holds holds = new Stuck.Holds();
        Object owner = new Object();
        holds.acquire(owner, 1, true);
        assertFalse(holds.tick());
        assertFalse(holds.contains(owner));
        holds.acquire(owner, 5, true);
        assertFalse(holds.release(owner));
    }

    @Test
    void leaseExpiresAfterItsRequestedFullTicks() {
        Stuck.Holds holds = new Stuck.Holds();
        Object owner = new Object();
        holds.acquire(owner, 2, false);
        assertTrue(holds.contains(owner));
        assertFalse(holds.tick());
        assertTrue(holds.contains(owner));
        assertTrue(holds.tick());
        assertFalse(holds.contains(owner));
    }

    @Test
    void renewalDoesNotLoseAutomaticOwnership() {
        Stuck.Holds holds = new Stuck.Holds();
        Object owner = new Object();
        holds.acquire(owner, 2, false);
        assertFalse(holds.tick());
        holds.acquire(owner, 2, true);
        assertFalse(holds.tick());
        assertTrue(holds.tick());
    }

    @Test
    void leasesHaveABoundedLifetimeEvenForInvalidDurations() {
        Stuck.Holds holds = new Stuck.Holds();
        Object owner = new Object();
        holds.acquire(owner, Integer.MAX_VALUE, false);
        for (int tick = 0; tick < 199; tick++) assertFalse(holds.tick());
        assertTrue(holds.tick());
        holds.clear();
        holds.acquire(owner, -1, false);
        assertTrue(holds.tick());
    }

    @Test
    void callerIdentityAndManualDisableInvalidateOldOwnership() {
        Stuck.Holds holds = new Stuck.Holds();
        Object first = new String("same-value");
        Object second = new String("same-value");
        holds.acquire(first, 20, false);
        assertFalse(holds.contains(second));
        assertFalse(holds.release(second));
        assertTrue(holds.contains(first));
        holds.clear();
        assertFalse(holds.contains(first));
        assertFalse(holds.release(first));
        holds.acquire(second, 20, true);
        assertFalse(holds.release(second));
    }

    @Test
    void freezingRetainsCrouchPoseButRemovesMovementJumpAndSprint() {
        Input held = new Input(true, true, true, true, true, true, true);
        assertEquals(new Input(false, false, false, false, false, true, false), Stuck.stationary(held));
        assertEquals(Input.EMPTY, Stuck.stationary(new Input(true, false, false, true, true, false, true)));
    }
}
