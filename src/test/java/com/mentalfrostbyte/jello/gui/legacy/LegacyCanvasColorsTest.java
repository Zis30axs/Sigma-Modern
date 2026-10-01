package com.mentalfrostbyte.jello.gui.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The old {@code RenderUtil2} colour helpers, which every ported screen leans on. */
class LegacyCanvasColorsTest {
    @Test
    void alphaReplacesRatherThanMultiplies() {
        assertEquals(0xFF, LegacyCanvas.alpha(0x10FFFFFF, 1.0F) >>> 24);
        assertEquals(0x00, LegacyCanvas.alpha(0xFFFFFFFF, 0.0F) >>> 24);
        assertEquals(0x7F, LegacyCanvas.alpha(0xFFFFFFFF, 0.5F) >>> 24);
    }

    @Test
    void fadeMultipliesTheColoursOwnAlpha() {
        assertEquals(0x40, LegacyCanvas.fade(0x80FFFFFF, 0.5F) >>> 24);
        assertEquals(0x80, LegacyCanvas.fade(0x80FFFFFF, 1.0F) >>> 24);
        assertEquals(0x00, LegacyCanvas.fade(0x80FFFFFF, -3.0F) >>> 24);
    }

    @Test
    void shiftTowardsOtherBlendsBetweenTheTwoColours() {
        assertEquals(0xFF000000, LegacyCanvas.shiftTowardsOther(0xFFFFFFFF, 0xFF000000, 0.0F));
        assertEquals(0xFFFFFFFF, LegacyCanvas.shiftTowardsOther(0xFFFFFFFF, 0xFF000000, 1.0F));
        int mid = LegacyCanvas.shiftTowardsOther(0xFFFFFFFF, 0xFF000000, 0.5F) & 0xFF;
        assertTrue(Math.abs(mid - 0x80) <= 1, "halfway, was " + mid);
    }
}
