package com.mentalfrostbyte.jello.gui.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyScrollTest {
    private final LegacyScroll scroll = new LegacyScroll(LegacyScroll.Style.JELLO);

    @Test
    void aWheelNotchMovesTheContentThirtyFivePixels() {
        assertTrue(scroll.wheel(-1, 1000, 300));
        assertEquals(35, scroll.offset());
        scroll.wheel(1, 1000, 300);
        assertEquals(0, scroll.offset());
    }

    @Test
    void theOffsetNeverLeavesTheContent() {
        for (int i = 0; i < 100; i++) {
            scroll.wheel(-1, 1000, 300);
        }
        assertEquals(700, scroll.offset());
        for (int i = 0; i < 100; i++) {
            scroll.wheel(1, 1000, 300);
        }
        assertEquals(0, scroll.offset());
    }

    @Test
    void contentThatFitsDoesNotScroll() {
        assertFalse(scroll.wheel(-1, 300, 300));
        assertEquals(0, scroll.offset());
        scroll.setOffset(50);
        scroll.clamp(300, 300);
        assertEquals(0, scroll.offset());
    }

    @Test
    void clickingTheTrackBelowTheThumbPagesByAQuarterOfTheContent() {
        int listRight = 500;
        int x = LegacyScroll.trackX(listRight) + 3;
        // A 1000 px list in a 300 px view: the thumb is 90 px, at the top; y = 250 is well under it.
        assertTrue(scroll.press(x, 250, listRight, 0, 300, 1000, 300));
        assertEquals(250, scroll.offset());
        assertFalse(scroll.dragging());
    }

    @Test
    void draggingTheThumbToTheBottomScrollsToTheEnd() {
        int listRight = 500;
        int x = LegacyScroll.trackX(listRight) + 3;
        assertTrue(scroll.press(x, 20, listRight, 0, 300, 1000, 300));
        assertTrue(scroll.dragging());
        scroll.drag(10_000, 0, 300, 1000, 300);
        assertEquals(700, scroll.offset());
        scroll.drag(-10_000, 0, 300, 1000, 300);
        assertEquals(0, scroll.offset());
        scroll.release();
        assertFalse(scroll.dragging());
    }

    @Test
    void aPressOutsideTheTrackIsNotTheBars() {
        assertFalse(scroll.press(10, 100, 500, 0, 300, 1000, 300));
    }
}
