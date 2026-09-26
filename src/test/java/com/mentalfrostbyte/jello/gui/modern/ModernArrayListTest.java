package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.impl.gui.ModuleArrayList.ColorMode;
import org.junit.jupiter.api.Test;

class ModernArrayListTest {

    private static final int BASE = 0xC0EAF6FF;

    @Test
    void staticIsTheChosenColorEverywhere() {
        assertEquals(BASE, ModernArrayList.lineColor(ColorMode.STATIC, BASE, 0F, 0F));
        assertEquals(BASE, ModernArrayList.lineColor(ColorMode.STATIC, BASE, 7.5F, 123F));
    }

    @Test
    void theWaveOnlyDimsTheChosenColorAndKeepsItsAlpha() {
        for (float position = 0F; position < 20F; position += 0.5F) {
            int color = ModernArrayList.lineColor(ColorMode.WAVE, BASE, position, 1.3F);
            assertEquals(BASE >>> 24, color >>> 24);
            for (int shift = 0; shift <= 16; shift += 8) {
                int channel = color >> shift & 0xFF, base = BASE >> shift & 0xFF;
                assertTrue(channel <= base && channel >= Math.round(base * 0.55F) - 1, "between 55% and full brightness");
            }
        }
        assertNotEquals(ModernArrayList.lineColor(ColorMode.WAVE, BASE, 0F, 0F),
                ModernArrayList.lineColor(ColorMode.WAVE, BASE, 3F, 0F), "it runs down the list");
    }

    @Test
    void theRainbowIsOpaqueAndChangesDownTheList() {
        int first = ModernArrayList.lineColor(ColorMode.RAINBOW, BASE, 0F, 0F);
        assertEquals(0xFF, first >>> 24);
        assertNotEquals(first, ModernArrayList.lineColor(ColorMode.RAINBOW, BASE, 5F, 0F));
        assertEquals(0xFF, ModernArrayList.lineColor(ColorMode.RAINBOW, BASE, 3F, 1000.25F) >>> 24, "any time, no overflow");
    }

    @Test
    void theSuffixFollowsTheNameAfterASpace() {
        assertEquals("Speed", ModernArrayList.label("Speed", null));
        assertEquals("Speed Legit Hop", ModernArrayList.label("Speed", "Legit Hop"));
    }
}
