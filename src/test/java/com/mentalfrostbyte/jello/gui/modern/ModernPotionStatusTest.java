package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.impl.gui.PotionStatus;
import java.util.List;
import java.util.function.Function;
import net.minecraft.world.effect.MobEffectCategory;
import org.junit.jupiter.api.Test;

class ModernPotionStatusTest {

    @Test
    void cardsSlimDownOnlyWhenFullOnesWouldReachAListBelow() {
        ModernPotionStatus.Size full = ModernPotionStatus.Size.FULL, slim = ModernPotionStatus.Size.SLIM;
        assertEquals(0, full.stack(0));
        assertEquals(30, full.stack(1));
        assertEquals(30 * 3 + 4 * 2, full.stack(3));
        assertTrue(slim.stack(8) < full.stack(8));

        int room = full.stack(6);
        assertEquals(full, ModernPotionStatus.Size.fitting(6, room, full), "exactly fits");
        assertEquals(slim, ModernPotionStatus.Size.fitting(7, room, full), "one more would reach the list");
        // Back to full only with room to spare, so a list easing across the edge doesn't flip them every frame.
        assertEquals(slim, ModernPotionStatus.Size.fitting(6, room, slim));
        assertEquals(full, ModernPotionStatus.Size.fitting(6, room + ModernPotionStatus.Size.SETTLE, slim));
        // The slim card still puts the text after the icon tile, inside the card.
        assertTrue(slim.textX() > slim.tile() && slim.textTop() + 11 * slim.nameScale() < slim.height() - slim.barBottom() - slim.barH());
        assertTrue(full.textTop() + 11 * full.nameScale() < full.height() - full.barBottom() - full.barH());
    }

    @Test
    void withoutRoomForEveryCardTheOnesWithMostTimeLeftFold() {
        PotionStatus.Entry regen = entry("Regeneration", 100, false), speed = entry("Speed", 6000, false),
            vision = entry("Night Vision", -1, true), poison = entry("Poison", 400, false), haste = entry("Haste", 2400, false);
        List<PotionStatus.Entry> sorted = List.of(regen, speed, vision, haste, poison);
        assertEquals(List.of(regen, haste, poison), ModernPotionStatus.keepSoonest(sorted, 3, Function.identity()),
            "the soonest three, still in the sort's order");
        assertEquals(List.of(regen, speed, haste, poison), ModernPotionStatus.keepSoonest(sorted, 4, Function.identity()),
            "an endless effect folds first");
        assertEquals(sorted, ModernPotionStatus.keepSoonest(sorted, 9, Function.identity()));
        assertEquals(List.of(), ModernPotionStatus.keepSoonest(sorted, 0, Function.identity()));

        ModernPotionStatus.Size slim = ModernPotionStatus.Size.SLIM;
        assertEquals(0, slim.capacity(slim.height() - 1));
        assertEquals(1, slim.capacity(slim.height()));
        assertEquals(5, slim.capacity(slim.stack(5)));
        assertEquals(5, slim.capacity(slim.stack(6) - 1));
    }

    private static PotionStatus.Entry entry(String name, int ticks, boolean infinite) {
        return new PotionStatus.Entry(name, MobEffectCategory.BENEFICIAL, ticks, infinite, false, true);
    }

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
