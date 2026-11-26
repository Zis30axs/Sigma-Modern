package com.mentalfrostbyte.jello.module.impl.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec2;
import org.junit.jupiter.api.Test;

class NoSlowTest {
    private static final float EPS = 1.0E-5F;
    private static final Vec2 FULL_INPUT = new Vec2(0.5F, 0.98F);

    @Test
    void registersInMovementCategoryWithExpectedSettingsAndLabels() {
        NoSlow noSlow = new NoSlow();
        ModuleManager manager = new ModuleManager();
        manager.register(noSlow);

        assertSame(noSlow, manager.get(NoSlow.class));
        assertSame(ModuleCategory.MOVEMENT, noSlow.getCategory());
        assertEquals("Vanilla", EnumSetting.label(NoSlow.Mode.VANILLA));
        assertEquals("GrimTest", EnumSetting.label(NoSlow.Mode.GRIM_TEST));
        assertSame(NoSlow.Mode.VANILLA, noSlow.getMode());

        // Vanilla settings are visible in Vanilla mode; GrimTest settings are hidden.
        assertTrue(noSlow.getForwardMultiplierSetting().isVisible());
        assertTrue(noSlow.getStrafeMultiplierSetting().isVisible());
        assertFalse(noSlow.getContainerBypassSetting().isVisible());
        assertFalse(noSlow.getCloseContainerSetting().isVisible());

        // Switching to GrimTest reveals Container Bypass & Close Container and hides Vanilla sliders.
        noSlow.setMode(NoSlow.Mode.GRIM_TEST);
        assertFalse(noSlow.getForwardMultiplierSetting().isVisible());
        assertFalse(noSlow.getStrafeMultiplierSetting().isVisible());
        assertTrue(noSlow.getContainerBypassSetting().isVisible());
        assertTrue(noSlow.getCloseContainerSetting().isVisible());

        noSlow.setContainerBypass(false);
        assertTrue(noSlow.getContainerBypassSetting().isVisible());
        assertFalse(noSlow.getCloseContainerSetting().isVisible());
    }

    @Test
    void vanillaModeAppliesConfiguredMultipliersAndSprintOption() {
        NoSlow noSlow = new NoSlow();
        noSlow.setMode(NoSlow.Mode.VANILLA);
        noSlow.setVanillaMultipliers(0.85F, 0.65F);
        noSlow.setAllowSprint(true);

        assertTrue(noSlow.stepItemUseTick(true, false, 0, InteractionHand.MAIN_HAND));
        assertTrue(noSlow.isAllowSprintThisTick());
        Vec2 scaled = noSlow.applyInputMultiplier(FULL_INPUT, 0.2F);
        assertEquals(FULL_INPUT.x * 0.65F, scaled.x, EPS);
        assertEquals(FULL_INPUT.y * 0.85F, scaled.y, EPS);

        // Full 1.0/1.0 leaves the input vector at full speed.
        noSlow.setVanillaMultipliers(1.0F, 1.0F);
        Vec2 full = noSlow.applyInputMultiplier(FULL_INPUT, 0.2F);
        assertEquals(FULL_INPUT.x, full.x, EPS);
        assertEquals(FULL_INPUT.y, full.y, EPS);

        // Disabling Allow Sprint preserves NoSlow movement while disallowing sprinting.
        noSlow.setAllowSprint(false);
        assertTrue(noSlow.stepItemUseTick(true, false, 0, InteractionHand.MAIN_HAND));
        assertFalse(noSlow.isAllowSprintThisTick());
    }

    @Test
    void grimTestAlternatesStartingWithSlowedTickToPreventTwoTickSetback() {
        NoSlow noSlow = new NoSlow();
        noSlow.setMode(NoSlow.Mode.GRIM_TEST);
        noSlow.setAllowSprint(true);

        for (int tick = 0; tick < 8; tick++) {
            boolean expectedBypassed = (tick % 2) == 1;
            assertEquals(expectedBypassed,
                    noSlow.stepItemUseTick(true, false, 2, InteractionHand.MAIN_HAND),
                    "tick " + tick + " slowed/un-slowed cadence");
            assertEquals(expectedBypassed, noSlow.isAllowSprintThisTick());

            Vec2 moved = noSlow.applyInputMultiplier(FULL_INPUT, 0.2F);
            float expectedScale = expectedBypassed ? 1.0F : 0.2F;
            assertEquals(FULL_INPUT.x * expectedScale, moved.x, EPS);
            assertEquals(FULL_INPUT.y * expectedScale, moved.y, EPS);
        }
    }

    @Test
    void grimTestResetsAlternationWhenItemUseEndsOrRestarts() {
        NoSlow noSlow = new NoSlow();
        noSlow.setMode(NoSlow.Mode.GRIM_TEST);

        // Tick 0 slowed, tick 1 un-slowed (player now has flaggedLastTick = true in Grim).
        assertFalse(noSlow.stepItemUseTick(true, false, 0, InteractionHand.MAIN_HAND));
        assertTrue(noSlow.stepItemUseTick(true, false, 0, InteractionHand.MAIN_HAND));

        // Ending use on !usingItem or boundary reset forces the next use to start slowed again.
        assertFalse(noSlow.stepItemUseTick(false, false, 0, InteractionHand.MAIN_HAND));
        assertFalse(noSlow.stepItemUseTick(true, false, 0, InteractionHand.MAIN_HAND));
        assertTrue(noSlow.stepItemUseTick(true, false, 0, InteractionHand.MAIN_HAND));

        noSlow.resetState();
        assertFalse(noSlow.stepItemUseTick(true, false, 0, InteractionHand.MAIN_HAND));
    }

    @Test
    void grimTestContainerOpenLatchesFullNoSlowUntilSessionEndsOrServerResyncs() {
        NoSlow noSlow = new NoSlow();
        noSlow.setMode(NoSlow.Mode.GRIM_TEST);
        noSlow.setContainerBypass(true);
        noSlow.setAllowSprint(true);

        // Not using an item -> opening a container must not be intercepted or latch bypass.
        assertFalse(noSlow.onContainerOpenedWhileUsing(false));
        assertFalse(noSlow.isContainerBypassActive());

        // Start eating on tick 0 (slowed), then receive Open_Window -> 100% NoSlow stays active.
        assertFalse(noSlow.stepItemUseTick(true, false, 4, InteractionHand.MAIN_HAND));
        assertTrue(noSlow.onContainerOpenedWhileUsing(true));
        assertTrue(noSlow.isContainerBypassActive());
        assertTrue(noSlow.isSuppressSlowdownThisTick());
        assertTrue(noSlow.isAllowSprintThisTick());

        for (int tick = 1; tick <= 6; tick++) {
            assertTrue(noSlow.stepItemUseTick(true, false, 4, InteractionHand.MAIN_HAND),
                    "100% NoSlow stays active across ticks after Open_Window");
            Vec2 moved = noSlow.applyInputMultiplier(FULL_INPUT, 0.2F);
            assertEquals(FULL_INPUT.x, moved.x, EPS);
            assertEquals(FULL_INPUT.y, moved.y, EPS);
        }

        // If fresh server metadata confirms active item use after Open_Window, Grim re-enables slowdown,
        // so NoSlow drops back cleanly to safe 1-tick alternation starting from a slowed tick.
        noSlow.onServerEntityUsingItem(true);
        assertFalse(noSlow.isContainerBypassActive());
        assertFalse(noSlow.stepItemUseTick(true, false, 4, InteractionHand.MAIN_HAND));
        assertTrue(noSlow.stepItemUseTick(true, false, 4, InteractionHand.MAIN_HAND));

        // Latching again then changing hotbar slot or stopping item use clears the container latch.
        assertTrue(noSlow.onContainerOpenedWhileUsing(true));
        assertTrue(noSlow.isContainerBypassActive());
        assertFalse(noSlow.stepItemUseTick(true, false, 5, InteractionHand.MAIN_HAND));
        assertFalse(noSlow.isContainerBypassActive());

        assertTrue(noSlow.onContainerOpenedWhileUsing(true));
        noSlow.onServerEntityUsingItem(false);
        assertFalse(noSlow.isContainerBypassActive());
    }

    @Test
    void grimTestResetsWhenSwitchingHandOrMountingVehicle() {
        NoSlow noSlow = new NoSlow();
        noSlow.setMode(NoSlow.Mode.GRIM_TEST);

        assertFalse(noSlow.stepItemUseTick(true, false, 1, InteractionHand.MAIN_HAND));
        assertTrue(noSlow.onContainerOpenedWhileUsing(true));
        assertTrue(noSlow.stepItemUseTick(true, false, 1, InteractionHand.MAIN_HAND));

        // Switching to off-hand clears prior latch and restarts from tick 0 (slowed).
        assertFalse(noSlow.stepItemUseTick(true, false, 1, InteractionHand.OFF_HAND));
        assertFalse(noSlow.isContainerBypassActive());

        // Becoming a passenger resets state cleanly.
        assertFalse(noSlow.stepItemUseTick(true, true, 1, InteractionHand.OFF_HAND));
        assertFalse(noSlow.isSuppressSlowdownThisTick());
    }

    @Test
    void staticHooksAreNullSafeWithoutRunningMinecraftInstance() {
        Vec2 input = new Vec2(0.0F, 1.0F);
        Vec2 vanilla = NoSlow.scaleItemUseInput(null, input, 0.2F);
        assertEquals(0.0F, vanilla.x, EPS);
        assertEquals(0.2F, vanilla.y, EPS);
        assertFalse(NoSlow.allowSprintWhileUsing(null));
        assertFalse(NoSlow.onOpenScreen(null, 1));
        NoSlow.onItemUseBoundary(null);
        NoSlow.onServerUsingItemData(null, true);
    }
}
