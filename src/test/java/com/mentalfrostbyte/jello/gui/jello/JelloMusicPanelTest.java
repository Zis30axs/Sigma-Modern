package com.mentalfrostbyte.jello.gui.jello;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JelloMusicPanelTest {

    @Test
    void timesReadAsMinutesAndTwoDigitSeconds() {
        assertEquals("0:00", JelloMusicPanel.time(0L));
        assertEquals("0:48", JelloMusicPanel.time(48_000L));
        assertEquals("3:22", JelloMusicPanel.time(202_400L));
        assertEquals("61:05", JelloMusicPanel.time(3_665_000L));
    }

    @Test
    void anUnknownOrNegativeLengthReadsAsZero() {
        assertEquals("0:00", JelloMusicPanel.time(-5L));
    }
}
