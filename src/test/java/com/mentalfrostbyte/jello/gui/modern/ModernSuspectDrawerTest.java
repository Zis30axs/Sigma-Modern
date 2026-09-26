package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModernSuspectDrawerTest {

    @Test
    void shutHidesTheWindowPastTheLeftEdgeAndOpenLeavesAMargin() {
        assertEquals(0, ModernSuspectDrawer.windowRight(0F), "shut: the window's right edge is the screen's left edge");
        assertEquals(ModernSuspectDrawer.WIDTH + 10, ModernSuspectDrawer.windowRight(1F));
    }

    @Test
    void theSpringMayOvershootAHairPastShutButNeverFarOff() {
        assertTrue(ModernSuspectDrawer.windowRight(-0.05F) < 0);
        assertEquals(ModernSuspectDrawer.windowRight(-0.05F), ModernSuspectDrawer.windowRight(-3F), "clamped");
    }

    @Test
    void aFlickDecidesByDirectionAndASlowReleaseByHowFarOut() {
        assertTrue(ModernSuspectDrawer.settleOpen(2F, 0.1F), "a fast flick out opens it even from barely out");
        assertFalse(ModernSuspectDrawer.settleOpen(-2F, 0.9F), "a fast flick in shuts it even from nearly open");
        assertTrue(ModernSuspectDrawer.settleOpen(0F, 0.6F));
        assertFalse(ModernSuspectDrawer.settleOpen(0F, 0.3F));
    }

    @Test
    void theWindowFitsItsRowsBetweenTheEmptyStatesFloorAndTheBand() {
        int band = 700;
        assertEquals(216, ModernSuspectDrawer.windowHeight(0, band), "empty: three rows tall, enough for the message");
        assertEquals(216, ModernSuspectDrawer.windowHeight(3, band));
        assertTrue(ModernSuspectDrawer.windowHeight(6, band) > ModernSuspectDrawer.windowHeight(3, band), "grows with its rows");
        assertEquals(band, ModernSuspectDrawer.windowHeight(500, band), "never taller than the band");
        assertEquals(150, ModernSuspectDrawer.windowHeight(0, 150), "a short band wins over the rows");
    }
}
