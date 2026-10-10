package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModernTargetHudTest {
    @Test
    void aNewTargetOrSessionNeverInheritsSomeoneElsesHealth() {
        ModernTargetHud.HealthAnimation animation = new ModernTargetHud.HealthAnimation();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(1F, animation.update(a, 1L, 20F, 20F, 0F, 0L).health());
        ModernTargetHud.Bars hurt = animation.update(a, 1L, 2F, 20F, 0F, 50_000_000L);
        assertTrue(hurt.health() > 0.1F && hurt.health() < 1F);
        assertTrue(hurt.trail() > hurt.health(), "a separate trail makes the amount of damage legible");
        assertEquals(0.5F, animation.update(b, 1L, 40F, 80F, 0F, 100_000_000L).health());
        assertEquals(0.1F, animation.update(b, 2L, 8F, 80F, 0F, 150_000_000L).health());
        animation.clear();
        assertEquals(1F, animation.update(b, 2L, 80F, 80F, 0F, 200_000_000L).health());
    }

    @Test
    void absorptionIsAdditionalHealthAndNeverMakesTheTrackOverflow() {
        ModernTargetHud.HealthAnimation animation = new ModernTargetHud.HealthAnimation();
        UUID id = UUID.randomUUID();
        ModernTargetHud.Bars start = animation.update(id, 1L, 20F, 20F, 20F, 0L);
        assertEquals(0.5F, start.health());
        assertEquals(0.5F, start.absorption());
        for (int frame = 1; frame <= 80; frame++) {
            ModernTargetHud.Bars bars = animation.update(id, 1L, 20F, 20F,
                    frame < 40 ? 0F : 80F, frame * 16_666_667L);
            assertTrue(bars.health() >= 0F && bars.absorption() >= 0F);
            assertTrue(bars.trail() >= bars.health());
            assertTrue(bars.health() + bars.absorption() <= 1.00001F);
            assertTrue(bars.trail() + bars.absorption() <= 1.00001F);
        }
    }

    @Test
    void longGapsAndInvalidHealthCannotLeaveAFrozenOrNonFiniteBar() {
        ModernTargetHud.HealthAnimation animation = new ModernTargetHud.HealthAnimation();
        UUID id = UUID.randomUUID();
        animation.update(id, 0L, 20F, 20F, 0F, 0L);
        ModernTargetHud.Bars afterPause = animation.update(id, 0L, 4F, 20F, 0F, 1_000_000_000L);
        assertEquals(0.2F, afterPause.health());
        assertEquals(afterPause.health(), afterPause.trail());
        ModernTargetHud.Bars invalid = animation.update(id, 1L, Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, 1_100_000_000L);
        assertEquals(0F, invalid.health());
        assertEquals(0F, invalid.absorption());
        ModernTargetHud.Bars huge = animation.update(id, 2L, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, 1_200_000_000L);
        assertEquals(0.5F, huge.health());
        assertEquals(0.5F, huge.absorption(), "the sum must use double precision rather than overflow float");
    }

    @Test
    void followModeDoesNotTurnMissingOrOffscreenProjectionIntoAnEdgeIndicator() {
        assertFalse(ModernTargetHud.onScreen(null, 320, 180));
        assertFalse(ModernTargetHud.onScreen(new NameTagProjection.Point(-0.01F, 90F), 320, 180));
        assertFalse(ModernTargetHud.onScreen(new NameTagProjection.Point(320F, 90F), 320, 180));
        assertFalse(ModernTargetHud.onScreen(new NameTagProjection.Point(160F, 180F), 320, 180));
        assertFalse(ModernTargetHud.onScreen(new NameTagProjection.Point(Float.NaN, 90F), 320, 180));
        assertTrue(ModernTargetHud.onScreen(new NameTagProjection.Point(160F, 90F), 320, 180));
    }

    @Test
    void aLargePanelFitsSmallGuiScalesAndDurabilityUsesTheRealItemMaximum() {
        float scale = ModernTargetHud.fittingScale(1.5F, 320, 180, 122);
        assertTrue(216 * scale <= 308F);
        assertTrue(122 * scale <= 168F);
        assertEquals(1F, ModernTargetHud.fittingScale(1F, 960, 540, 122));
        assertEquals(0F, ModernTargetHud.fittingScale(1F, 0, 0, 122));
        assertEquals(0.75F, ModernTargetHud.durability(50, 200));
        assertEquals(1F, ModernTargetHud.durability(-1, 200));
        assertEquals(0F, ModernTargetHud.durability(Integer.MAX_VALUE, 200));
        assertEquals(0F, ModernTargetHud.durability(1, 0));
    }

    @Test
    void equipmentChangesCannotResizeAPanelWhileItsPositionIsBeingEdited() {
        assertEquals(122, ModernTargetHud.panelHeight(true, true, 0));
        assertEquals(122, ModernTargetHud.panelHeight(true, true, 6));
        assertEquals(88, ModernTargetHud.panelHeight(true, false, 0));
        assertEquals(122, ModernTargetHud.panelHeight(true, false, 1));
        assertEquals(88, ModernTargetHud.panelHeight(false, true, 6));
    }
}
