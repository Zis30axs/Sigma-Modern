package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.impl.combat.Criticals.Offset;
import com.mentalfrostbyte.jello.module.impl.combat.Criticals.PacketMode;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import java.util.List;
import org.junit.jupiter.api.Test;

class CriticalsTest {

    /** The hops are LiquidBounce's {@code CriticalsPacket} numbers, digit for digit. */
    @Test
    void packetHopsAreUpstreams() {
        assertEquals(List.of(new Offset(0.2, false), new Offset(0.01, false)), Criticals.offsets(PacketMode.VANILLA, true, 0));
        assertEquals(List.of(new Offset(0.11, false), new Offset(0.1100013579, false), new Offset(0.0000013579, false)),
                Criticals.offsets(PacketMode.NO_CHEAT_PLUS, true, 0));
        assertEquals(List.of(new Offset(0.0625, false), new Offset(0.0625013579, false), new Offset(0.0000013579, false)),
                Criticals.offsets(PacketMode.FALLING, true, 0));
        assertEquals(List.of(new Offset(1.0E-9, false), new Offset(0.0, false)), Criticals.offsets(PacketMode.LOW, true, 0));
        assertEquals(List.of(new Offset(-1.0E-9, false)), Criticals.offsets(PacketMode.DOWN, true, 0));
    }

    @Test
    void grimOnlyDipsFromTheAir() {
        assertTrue(Criticals.offsets(PacketMode.GRIM, true, 0).isEmpty(), "on the ground there is no fall to deepen");
        assertEquals(List.of(new Offset(-0.000001, false)), Criticals.offsets(PacketMode.GRIM, false, 0));
    }

    @Test
    void blocksMcHopsEveryFourthTickAndClaimsGroundFirst() {
        for (int tick = 0; tick < 8; tick++) {
            List<Offset> hops = Criticals.offsets(PacketMode.BLOCKS_MC, true, tick);
            assertEquals(tick % 4 == 0, !hops.isEmpty(), "tick " + tick);
        }
        assertEquals(List.of(new Offset(0.0011, true), new Offset(0.0, false)), Criticals.offsets(PacketMode.BLOCKS_MC, true, 4));
    }

    @Test
    void theModesReadAsUpstreamNamesThem() {
        assertEquals("NoCheatPlus", EnumSetting.label(PacketMode.NO_CHEAT_PLUS));
        assertEquals("BlocksMC", EnumSetting.label(PacketMode.BLOCKS_MC));
    }

    /**
     * A normal jump tops out after 0.42 / 0.08 = 5.25 ticks, and upstream's no-landing estimate puts the landing at
     * 10: a jump is held back while the cooldown would still be recovering after the top plus one tick, unless it is so
     * far off that the whole jump could not cover it anyway.
     */
    @Test
    void jumpWaitsOnlyWhileTheCooldownWouldMissTheFall() {
        assertFalse(Criticals.shouldWaitForJump(0.0F), "ready now");
        assertFalse(Criticals.shouldWaitForJump(6.25F), "ready by the tick after the top");
        assertTrue(Criticals.shouldWaitForJump(7.0F));
        assertTrue(Criticals.shouldWaitForJump(15.0F));
        assertFalse(Criticals.shouldWaitForJump(16.0F), "too far off for waiting to help");
    }

    @Test
    void onlyTheCurrentModesSettingsAreShown() {
        Criticals criticals = new Criticals();
        assertEquals(ModuleCategory.COMBAT, criticals.getCategory());
        assertTrue(this.visible(criticals, "Packet Mode"));
        assertFalse(this.visible(criticals, "Height"));

        this.choose(criticals, Criticals.Mode.JUMP);
        assertFalse(this.visible(criticals, "Packet Mode"));
        assertTrue(this.visible(criticals, "Height"));
        assertTrue(this.visible(criticals, "Wait For Cooldown"));

        this.choose(criticals, Criticals.Mode.NO_GROUND);
        assertFalse(this.visible(criticals, "Packet Type"));
        assertFalse(this.visible(criticals, "Range"));
    }

    private boolean visible(final Criticals criticals, final String name) {
        return criticals.setting(name).map(Setting::isVisible).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private void choose(final Criticals criticals, final Criticals.Mode mode) {
        ((EnumSetting<Criticals.Mode>) criticals.setting("Mode").orElseThrow()).set(mode);
    }
}
