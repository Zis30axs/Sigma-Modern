package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModernPotionStatusTest {

    @Test
    void brightEffectColorsAreKept() {
        // Fire resistance's orange and slow falling's cream are already light enough.
        assertEquals(0xFFFF9900, ModernPotionStatus.iconColor(0xFF9900));
        assertEquals(0xFFF3CFB9, ModernPotionStatus.iconColor(0xF3CFB9));
    }

    @Test
    void darkEffectColorsAreLightenedEnoughToReadOnDarkGlassAndKeepTheirHue() {
        // Blindness, darkness, wither, bad omen: near black to dim.
        for (int rgb : new int[]{0x1F1F23, 0x292721, 0x736156, 0x0B6138, 0x000000}) {
            int shown = ModernPotionStatus.iconColor(rgb);
            assertEquals(0xFF, shown >>> 24);
            assertTrue(ModernPotionStatus.luma(shown & 0xFFFFFF) >= ModernPotionStatus.MIN_LUMA - 0.01F,
                Integer.toHexString(rgb) + " -> " + Integer.toHexString(shown));
        }
        // Bad omen's green stays greener than it is red once lightened.
        int omen = ModernPotionStatus.iconColor(0x0B6138);
        assertTrue((omen >> 8 & 0xFF) > (omen >> 16 & 0xFF), Integer.toHexString(omen));
    }
}
