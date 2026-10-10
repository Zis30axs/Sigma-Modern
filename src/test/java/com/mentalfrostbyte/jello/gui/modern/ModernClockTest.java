package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.impl.gui.HudClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ModernClockTest {
    @Test
    void midnightAndNoonHaveTheCorrectTwelveHourSuffix() {
        assertEquals("00:00:00", ModernClock.formatTime(LocalTime.MIDNIGHT, true, true));
        assertEquals("12:00:00 AM", ModernClock.formatTime(LocalTime.MIDNIGHT, false, true));
        assertEquals("12:00 PM", ModernClock.formatTime(LocalTime.NOON, false, false));
        assertEquals("11:59:59 PM", ModernClock.formatTime(LocalTime.of(23, 59, 59), false, true));
        assertEquals("01:05", ModernClock.formatTime(LocalTime.of(1, 5, 9), true, false));
        assertEquals("01:05 AM", ModernClock.formatTime(LocalTime.of(1, 5, 9), false, false));
    }

    @Test
    void dateAndTimeAdvanceTogetherAtLocalMidnightIncludingLeapDay() {
        ModernClock.TimeCache cache = new ModernClock.TimeCache();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long beforeMidnight = Instant.parse("2024-02-28T15:59:59Z").toEpochMilli();
        var before = cache.update(beforeMidnight, zone, true, true, true);
        assertEquals("2024-02-28", before.dateText());
        assertEquals("23:59:59", before.timeText());
        var after = cache.update(beforeMidnight + 1000L, zone, true, true, true);
        assertEquals("2024-02-29", after.dateText());
        assertEquals("00:00:00", after.timeText());
        assertEquals("2025-01-01", ModernClock.formatDate(LocalDate.of(2024, 12, 31).plusDays(1)));
    }

    @Test
    void cacheReusesASecondButImmediatelyAppliesFormattingAndZoneChanges() {
        ModernClock.TimeCache cache = new ModernClock.TimeCache();
        ZoneId utc = ZoneId.of("UTC");
        long noon = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli();
        var original = cache.update(noon, utc, true, true, true);
        assertSame(original, cache.update(noon + 999L, utc, true, true, true));
        var shortTime = cache.update(noon + 999L, utc, false, false, false);
        assertNotSame(original, shortTime);
        assertEquals("12:00 PM", shortTime.timeText());
        assertEquals("", shortTime.dateText());
        var shifted = cache.update(noon, ZoneId.of("Asia/Shanghai"), true, true, true);
        assertEquals("20:00:00", shifted.timeText());
        assertEquals("11:59:59", cache.update(noon - 1L, utc, true, true, true).timeText(),
                "a backward wall-clock correction must not wait for the previous second to catch up");
    }

    @Test
    void systemZoneRulesHandleDaylightSavingWithoutInventingMissingLocalTimes() {
        ModernClock.TimeCache cache = new ModernClock.TimeCache();
        ZoneId zone = ZoneId.of("America/New_York");
        long spring = Instant.parse("2026-03-08T06:59:59Z").toEpochMilli();
        assertEquals("01:59:59", cache.update(spring, zone, true, true, true).timeText());
        assertEquals("03:00:00", cache.update(spring + 1000L, zone, true, true, true).timeText());
        long autumn = Instant.parse("2026-11-01T05:59:59Z").toEpochMilli();
        assertEquals("01:59:59", cache.update(autumn, zone, true, true, true).timeText());
        assertEquals("01:00:00", cache.update(autumn + 1000L, zone, true, true, true).timeText());
    }

    @Test
    void clockHandsTurnClockwiseAndContinueMovingBetweenTicks() {
        var midnight = ModernClock.angles(LocalTime.MIDNIGHT, 0.0);
        assertEquals(0.0, midnight.hour());
        assertEquals(0.0, midnight.minute());
        assertEquals(0.0, midnight.second());
        var quarter = ModernClock.angles(LocalTime.of(3, 15, 30), 0.5);
        assertEquals(30.5 / 60.0, quarter.second(), 1E-10);
        assertEquals((15.0 + 30.5 / 60.0) / 60.0, quarter.minute(), 1E-10);
        assertEquals((3.0 + (15.0 + 30.5 / 60.0) / 60.0) / 12.0, quarter.hour(), 1E-10);
        assertEquals(ModernClock.angles(LocalTime.NOON, 0.0), ModernClock.angles(LocalTime.MIDNIGHT, Double.NaN));
    }

    @Test
    void fixedCardBoundsFitSmallViewportsAndLeaveRoomForChat() {
        for (HudClock.Style style : HudClock.Style.values()) {
            for (boolean date : new boolean[] {false, true}) {
                var size = ModernClock.size(style, date);
                float scale = ModernClock.fittingScale(1.5F, size, 120, 100);
                assertTrue(size.width() * scale <= 108F);
                assertTrue(size.height() * scale <= 88F);
                assertEquals(0F, ModernClock.fittingScale(Float.NaN, size, 120, 100));
                assertEquals(0F, ModernClock.fittingScale(1F, size, 0, 0));
                float top = ModernClock.defaultY(540, size.height(), 90F, 120F);
                assertTrue(top + size.height() <= 390F, "chat and hotbar keep their reserved bottom area");
                assertTrue(top >= 126F, "the existing left stack keeps its clearance");
            }
        }
        assertEquals(166F, ModernClock.defaultY(270, 62F, 90F, 160F),
                "on a short screen the left stack takes precedence over an empty reserved chat page");
        assertEquals(6F, ModernClock.defaultY(100, 140F, 180F, 120F));
    }

    @Test
    void everyDigitalFormatFitsTheFixedCardWithTabularDigitSpacing() {
        float digit = 0F;
        for (int i = 0; i < 10; i++) digit = Math.max(digit, ModernTypography.width(Integer.toString(i)));
        for (boolean twentyFourHour : new boolean[] {false, true}) {
            for (boolean seconds : new boolean[] {false, true}) {
                String text = ModernClock.formatTime(LocalTime.of(23, 59, 59), twentyFourHour, seconds);
                float width = 0F;
                for (int i = 0; i < text.length(); i++) {
                    char ch = text.charAt(i);
                    width += Character.isDigit(ch) ? digit : ModernTypography.width(Character.toString(ch));
                }
                assertTrue(width * 1.5F <= ModernClock.size(HudClock.Style.DIGITAL, true).width() - 20F,
                        "the longest selected format needs to retain its side padding: " + text);
            }
        }
    }

    @Test
    void theBoundedDialMaskContainsAntialiasedTicksAndATransparentCenter() {
        assertEquals(64, ModernClock.faceSize(1F));
        assertEquals(128, ModernClock.faceSize(100F));
        assertEquals(512, ModernClock.faceSize(10000F));
        var image = ModernClock.faceMask(160);
        assertEquals(0, image.getRGB(80, 80) >>> 24);
        assertEquals(0, image.getRGB(0, 0) >>> 24);
        int ink = 0, partial = 0;
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha > 0) ink++;
                if (alpha > 0 && alpha < 220) partial++;
            }
        }
        assertTrue(ink > 500 && ink < image.width() * image.height() / 3);
        assertTrue(partial > 200, "thin ticks and the circular rim need a smooth coverage edge");
    }
}
