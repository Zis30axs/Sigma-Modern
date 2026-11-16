package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.impl.combat.crystalaura.CrystalDamage;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Pure-logic tests for the CrystalAura damage math: no game, no world, no network. */
class CrystalAuraTest {

    private static final CrystalDamage.DamageOptions DEFAULTS = new CrystalDamage.DamageOptions(
            5.0F, 2.0F, 1.0F, true, true, true, true, true, 2, 2,
            CrystalDamage.CalcMode.BOTH, CrystalDamage.Logic.AND);

    @Test
    void rawExplosionDamageMatchesVanillaFormula() {
        // Point blank, full exposure: ((1 + 1) / 2) * 7 * 12 + 1 = 85.
        assertEquals(85.0F, CrystalDamage.rawExplosionDamage(0.0, 1.0F), 0.001F);
        // At the edge of the blast (12 blocks), the decay term is zero: only the +1 remains.
        assertEquals(1.0F, CrystalDamage.rawExplosionDamage(144.0, 1.0F), 0.001F);
        // No exposure at all: same +1 floor.
        assertEquals(1.0F, CrystalDamage.rawExplosionDamage(0.0, 0.0F), 0.001F);
        // 6 blocks out, full exposure: decay 0.5, ((0.25 + 0.5) / 2) * 84 + 1 = 32.5.
        assertEquals(32.5F, CrystalDamage.rawExplosionDamage(36.0, 1.0F), 0.001F);
        // Half exposure at point blank: ((0.25 + 0.5) / 2) * 84 + 1 = 32.5 as well.
        assertEquals(32.5F, CrystalDamage.rawExplosionDamage(0.0, 0.5F), 0.001F);
    }

    @Test
    void gatesRejectInUpstreamOrder() {
        // A good spot passes.
        assertTrue(CrystalDamage.passesGates(1.0F, 6.0F, 20.0F, 0.0F, DEFAULTS));
        // Below the minimum enemy damage.
        assertFalse(CrystalDamage.passesGates(1.0F, 4.9F, 20.0F, 0.0F, DEFAULTS));
        // Exactly the minimum passes.
        assertTrue(CrystalDamage.passesGates(1.0F, 5.0F, 20.0F, 0.0F, DEFAULTS));
        // Above the maximum self damage.
        assertFalse(CrystalDamage.passesGates(2.1F, 6.0F, 20.0F, 0.0F, DEFAULTS));
        // Exactly the maximum self damage passes.
        assertTrue(CrystalDamage.passesGates(2.0F, 6.0F, 20.0F, 0.0F, DEFAULTS));
        // Self damage at or above health + absorption is suicide.
        assertFalse(CrystalDamage.passesGates(20.0F, 30.0F, 20.0F, 0.0F, DEFAULTS));
        assertFalse(CrystalDamage.passesGates(19.0F, 30.0F, 15.0F, 4.0F, DEFAULTS));
        // Absorption counts towards the health that keeps you alive.
        assertTrue(CrystalDamage.passesGates(19.0F, 30.0F, 15.0F, 5.0F,
                new CrystalDamage.DamageOptions(5.0F, 20.0F, 1.0F, true, false, true,
                        true, true, 2, 2, CrystalDamage.CalcMode.BOTH, CrystalDamage.Logic.AND)));
        // Efficient: the enemy must be hurt strictly more than you. (Low minimums here so
        // only the efficient rule decides.)
        CrystalDamage.DamageOptions efficientOnly = new CrystalDamage.DamageOptions(
                1.0F, 20.0F, 1.0F, true, true, true,
                true, true, 2, 2, CrystalDamage.CalcMode.BOTH, CrystalDamage.Logic.AND);
        assertFalse(CrystalDamage.passesGates(6.0F, 6.0F, 20.0F, 0.0F, efficientOnly));
        assertTrue(CrystalDamage.passesGates(5.9F, 6.0F, 20.0F, 0.0F, efficientOnly));
    }

    @Test
    void candidateOrderIsEnemyDamageThenSelfDamageThenDistance() {
        // Higher enemy damage wins.
        assertTrue(CrystalDamage.compareCandidates(6.0F, 1.0F, 9.0, 5.0F, 0.0F, 1.0) < 0);
        // Tied enemy damage: lower self damage wins.
        assertTrue(CrystalDamage.compareCandidates(5.0F, 0.5F, 9.0, 5.0F, 1.0F, 1.0) < 0);
        // Tied damage: closer wins.
        assertTrue(CrystalDamage.compareCandidates(5.0F, 1.0F, 4.0, 5.0F, 1.0F, 9.0) < 0);
        // Identical candidates tie.
        assertEquals(0, CrystalDamage.compareCandidates(5.0F, 1.0F, 4.0, 5.0F, 1.0F, 4.0));
    }

    @Test
    void predictionIsLinearExtrapolation() {
        assertEquals(new Vec3(3.0, 2.0, 3.0),
                CrystalDamage.predictPosition(new Vec3(1.0, 2.0, 3.0), new Vec3(0.5, 0.0, 0.0), 4));
        assertEquals(new Vec3(1.0, 2.0, 3.0),
                CrystalDamage.predictPosition(new Vec3(1.0, 2.0, 3.0), new Vec3(0.5, 0.0, 0.0), 0));
    }

    @Test
    void predictedBoxKeepsSizeAndSitsOnPredictedFeet() {
        AABB box = new AABB(0.0, 0.0, 0.0, 1.0, 2.0, 1.0);
        AABB predicted = CrystalDamage.predictedBox(box, new Vec3(10.0, 5.0, 10.0));
        assertEquals(new AABB(9.5, 5.0, 9.5, 10.5, 7.0, 10.5), predicted);
    }

    @Test
    void crystalCenterAndBox() {
        assertEquals(new Vec3(3.5, 65.0, -1.5),
                CrystalDamage.crystalCenter(new net.minecraft.core.BlockPos(3, 64, -2)));
        assertEquals(new AABB(3.0, 65.0, -2.0, 4.0, 67.0, -1.0),
                CrystalDamage.crystalBox(new net.minecraft.core.BlockPos(3, 64, -2)));
    }

    @Test
    void willBeBlockedNeedsAnIntersection() {
        AABB crystal = new AABB(0.0, 65.0, 0.0, 1.0, 67.0, 1.0);
        assertTrue(CrystalDamage.willBeBlocked(crystal, new AABB(0.5, 65.0, 0.5, 1.5, 67.0, 1.5)));
        assertFalse(CrystalDamage.willBeBlocked(crystal, new AABB(5.0, 65.0, 5.0, 6.0, 67.0, 6.0)));
        assertFalse(CrystalDamage.willBeBlocked(crystal, null));
    }
}
