package com.mentalfrostbyte.jello.gui.legacy.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The compass's arithmetic: which headings are on the strip for the way you face. */
class JelloWidgetsTest {

    @Test
    void anAngleIsBroughtIntoOneTurn() {
        assertEquals(0.0F, JelloWidgets.normalize(0.0F));
        assertEquals(350.0F, JelloWidgets.normalize(-10.0F));
        assertEquals(10.0F, JelloWidgets.normalize(370.0F));
        assertEquals(0.0F, JelloWidgets.normalize(-360.0F));
    }

    @Test
    void theStripIsElevenHeadingsCentredOnTheNearestFifteenDegrees() {
        // Facing 92 degrees: the nearest step is 90, five steps each side.
        assertEquals(List.of(15, 30, 45, 60, 75, 90, 105, 120, 135, 150, 165), JelloWidgets.headings(92, 5));
    }

    @Test
    void theStripWrapsRoundNorthAndSouth() {
        // Facing 3 degrees: the nearest step is 0, so the strip runs back through 345 to 285.
        assertEquals(List.of(285, 300, 315, 330, 345, 0, 15, 30, 45, 60, 75), JelloWidgets.headings(3, 5));
    }

    @Test
    void justPastTheHalfwayPointItMovesToTheNextStep() {
        assertEquals(90, JelloWidgets.headings(83, 5).get(5));
        assertEquals(75, JelloWidgets.headings(82, 5).get(5));
    }
}
