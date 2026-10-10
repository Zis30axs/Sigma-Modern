package com.mentalfrostbyte.jello.module.impl.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NameTagsTest {

    @Test
    void healthUsesTheActualMaximumAndClampsRatherThanAssumingTwentyPoints() {
        assertEquals(0.5F, NameTags.healthFraction(40, 80));
        assertEquals(0.0F, NameTags.healthFraction(-3, 80));
        assertEquals(1.0F, NameTags.healthFraction(100, 80));
        assertEquals(1.0F, NameTags.healthFraction(Float.MAX_VALUE, Float.MIN_VALUE));
        assertEquals(0.0F, NameTags.healthFraction(20, 0));
        assertEquals(0.0F, NameTags.healthFraction(20, -1));
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertEquals(0.0F, NameTags.healthFraction(invalid, 20));
            assertEquals(0.0F, NameTags.healthFraction(20, invalid));
        }
    }

    @Test
    void halfPointRoundingNeverReportsALivingPlayerAsDead() {
        assertEquals("0", NameTags.formatHealth(0));
        assertEquals("0", NameTags.formatHealth(-0.0F));
        assertEquals("0.5", NameTags.formatHealth(Float.MIN_VALUE));
        assertEquals("0.5", NameTags.formatHealth(0.5F));
        assertEquals("1", NameTags.formatHealth(Math.nextUp(0.5F)));
        assertEquals("23.5", NameTags.formatHealth(23.01F));
        assertEquals("80", NameTags.formatHealth(80));
        assertEquals("9999", NameTags.formatHealth(9998.99F));
        assertEquals("9999", NameTags.formatHealth(9999));
        assertEquals("9999+", NameTags.formatHealth(Math.nextUp(9999.0F)));
        assertEquals("9999+", NameTags.formatHealth(Float.MAX_VALUE));
        for (float invalid : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertEquals("—", NameTags.formatHealth(invalid));
        }
    }

    @Test
    void healthColorsHaveStableThresholdsAndOpaqueInterpolatedChannels() {
        assertEquals(0xFFEF7F8E, NameTags.healthColor(0));
        assertEquals(0xFFE8C982, NameTags.healthColor(0.5F));
        assertEquals(0xFF86E3C3, NameTags.healthColor(1));
        assertEquals(NameTags.healthColor(0), NameTags.healthColor(Float.NaN));
        assertEquals(NameTags.healthColor(0), NameTags.healthColor(-1));
        assertEquals(NameTags.healthColor(1), NameTags.healthColor(2));
        int quarter = NameTags.healthColor(0.25F);
        assertEquals(0xFF, quarter >>> 24);
        assertTrue((quarter >>> 8 & 0xFF) > 0x7F && (quarter >>> 8 & 0xFF) < 0xC9);
        assertTrue((quarter >>> 16 & 0xFF) > 0xE8 && (quarter >>> 16 & 0xFF) < 0xEF);
    }

    @Test
    void distancesAndLatencyDistinguishUnknownValuesFromActualZero() {
        assertEquals("0m", NameTags.formatDistance(0));
        assertEquals("12m", NameTags.formatDistance(12.49));
        assertEquals("13m", NameTags.formatDistance(12.5));
        assertEquals("—m", NameTags.formatDistance(-1));
        assertEquals("—m", NameTags.formatDistance(Double.NaN));
        assertEquals("—m", NameTags.formatDistance(Double.POSITIVE_INFINITY));
        assertEquals("—ms", NameTags.formatPing(-1));
        assertEquals("—ms", NameTags.formatPing(Integer.MIN_VALUE));
        assertEquals("0ms", NameTags.formatPing(0));
        assertEquals("9999ms", NameTags.formatPing(9999));
        assertEquals("9999+ms", NameTags.formatPing(10000));
        assertEquals("9999+ms", NameTags.formatPing(Integer.MAX_VALUE));
    }

    @Test
    void serverLabelsLoseFormattingAndControlsWithoutLosingUnicodeNames() {
        assertEquals("玩家🦊", NameTags.boundedText("§a玩\n家§L🦊\u0000\t\u0085§r", 8));
        assertEquals("Name", NameTags.boundedText("§x§1§2§3§A§b§CName", 8));
        assertEquals("A🦊", NameTags.boundedText("A§🦊§", 8));
        assertEquals("abc", NameTags.boundedText("abc§r\n", 3));
    }

    @Test
    void truncationCountsCodePointsAndKeepsTheEllipsisInsideTheBudget() {
        assertEquals("玩🦊家", NameTags.boundedText("玩🦊家", 3));
        assertEquals("玩🦊…", NameTags.boundedText("玩🦊家XYZ", 3));
        assertEquals("…", NameTags.boundedText("🦊XYZ", 1));
        assertEquals("🦊", NameTags.boundedText("🦊§r", 1));
        assertEquals("", NameTags.boundedText("Name", 0));
        assertEquals("", NameTags.boundedText("Name", -1));
        assertEquals("", NameTags.boundedText(null, 10));
        assertEquals("", NameTags.boundedText("\n§r", 10));
    }
}
