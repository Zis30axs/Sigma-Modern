package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.impl.combat.TickBase.Step;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Proves TickBase's pure logic: balance math, boost ticks, landing delay, knockback, simulation. */
class TickBaseTest {

    private static final double DELTA = 1e-6;

    @Test
    void spendableTicksClampsToBalanceRange() {
        assertEquals(2, TickBase.spendableTicks(2.5F, 3));
        assertEquals(3, TickBase.spendableTicks(5.0F, 3));
        assertEquals(0, TickBase.spendableTicks(-1.0F, 3));
        assertEquals(0, TickBase.spendableTicks(0.0F, 3));
    }

    @Test
    void boostTicksPaysWithBalance() {
        // 3 balance at 5x: each boosted tick spends 1 - 1/5 balance, so 3 ticks.
        assertEquals(3, TickBase.boostTicks(3.0F, 3, 5.0F));
        // No boost at or below normal speed.
        assertEquals(0, TickBase.boostTicks(3.0F, 3, 1.0F));
        assertEquals(0, TickBase.boostTicks(3.0F, 3, 0.5F));
        // 2 balance at 2x: each boosted tick spends half a tick.
        assertEquals(4, TickBase.boostTicks(2.0F, 3, 2.0F));
        // Longer boosts are capped at 100 ticks: 40 balance at 1.5x pays for 120, capped to 100.
        assertEquals(100, TickBase.boostTicks(40.0F, 40, 1.5F));
    }

    @Test
    void balanceAfterFrameAccountsRealTimeMinusRunTicks() {
        // One tick at 0.9x takes 1/0.9 real ticks: behind by 1/0.9 - 1.
        assertEquals(1.0F / 0.9F - 1.0F, TickBase.balanceAfterFrame(1, 0.9F, 0, 0), 1e-6F);
        // Five ticks at 5x spend 4 balance.
        assertEquals(-4.0F, TickBase.balanceAfterFrame(5, 5.0F, 0, 0), 1e-6F);
        // A held tick banks one tick at normal speed.
        assertEquals(1.0F, TickBase.balanceAfterFrame(1, 1.0F, 1, 0), 1e-6F);
        // Four burst ticks spend four balance.
        assertEquals(-4.0F, TickBase.balanceAfterFrame(1, 1.0F, 0, 4), 1e-6F);
        // Ticks the vanilla 10-per-frame cap drops never ran and change nothing.
        assertEquals(0.0F, TickBase.balanceAfterFrame(50, 1.0F, 0, 0), 1e-6F);
        // Nothing happens at normal speed with no hold or burst.
        assertEquals(0.0F, TickBase.balanceAfterFrame(2, 1.0F, 0, 0), 1e-6F);
    }

    @Test
    void landingDelayCountsUnpaidTicksForHoldFirst() {
        // HoldFirst: the walk plus the ticks the balance does not pay for yet.
        assertEquals(4, TickBase.landingDelay(true, 5, 2, 1.0F));
        assertEquals(2, TickBase.landingDelay(true, 3, 2, 5.0F));
        // BurstFirst: only the walk.
        assertEquals(2, TickBase.landingDelay(false, 5, 2, 1.0F));
    }

    @Test
    void knockbackImpulseMatchesVanillaFormula() {
        Vec3 away = new Vec3(0.0, 0.0, 1.0);
        Vec3 knocked = TickBase.knockbackImpulse(new Vec3(1.0, 0.0, 0.0), away, 1.0, false);
        assertEquals(0.5, knocked.x, DELTA);
        assertEquals(0.0, knocked.y, DELTA);
        assertEquals(0.4, knocked.z, DELTA);

        Vec3 sprint = TickBase.knockbackImpulse(new Vec3(1.0, 0.0, 0.0), away, 1.0, true);
        assertEquals(0.25, sprint.x, DELTA);
        assertEquals(0.0, sprint.y, DELTA);
        assertEquals(0.7, sprint.z, DELTA);
    }

    @Test
    void stepPhysicsKeepsStandingPlayerOnTheGround() {
        Step next = TickBase.stepPhysics(new Step(new Vec3(0.0, 64.0, 0.0), new Vec3(0.2, 0.0, 0.0), true, 0.0), 64.0);
        assertEquals(0.2, next.pos().x, DELTA);
        assertEquals(64.0, next.pos().y, DELTA);
        assertTrue(next.onGround());
        assertEquals(0.0, next.fallDistance(), DELTA);
    }

    @Test
    void stepPhysicsAppliesGravityAndLandsOnFlatGround() {
        Step falling = TickBase.stepPhysics(
                new Step(new Vec3(0.0, 64.1, 0.0), new Vec3(0.0, -0.5, 0.0), false, 2.0), 64.0);
        assertEquals(64.0, falling.pos().y, DELTA);
        assertTrue(falling.onGround());
        assertEquals(0.0, falling.velocity().y, DELTA);
        assertEquals(0.0, falling.fallDistance(), DELTA);

        Step midAir = TickBase.stepPhysics(
                new Step(new Vec3(0.0, 70.0, 0.0), new Vec3(0.0, -0.5, 0.0), false, 1.0), 64.0);
        assertEquals(-0.58, midAir.velocity().y, DELTA);
        assertEquals(69.42, midAir.pos().y, DELTA);
        assertEquals(1.58, midAir.fallDistance(), DELTA);
        assertFalse(midAir.onGround());
    }

    @Test
    void stepPhysicsLeavesTheGroundOnJumpAndCapsFallSpeed() {
        Step jump = TickBase.stepPhysics(
                new Step(new Vec3(0.0, 64.0, 0.0), new Vec3(0.0, 0.42, 0.0), true, 0.0), 64.0);
        assertFalse(jump.onGround());
        assertEquals(0.34, jump.velocity().y, DELTA);
        assertEquals(64.34, jump.pos().y, DELTA);

        Step terminal = TickBase.stepPhysics(
                new Step(new Vec3(0.0, 100.0, 0.0), new Vec3(0.0, -5.0, 0.0), false, 10.0),
                Double.NEGATIVE_INFINITY);
        assertEquals(-3.92, terminal.velocity().y, DELTA);
        assertFalse(terminal.onGround());
    }
}
