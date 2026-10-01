package com.mentalfrostbyte.jello.gui.jello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JelloMapViewTest {

    @Test
    void aCornerIsCutDeepAtTheEdgeAndNotAtAllAtItsRadius() {
        // At the very top of a radius-14 corner only the last few pixels are inside; 14 down it is square.
        assertEquals(10, JelloMapView.inset(14, 0.5));
        assertEquals(0, JelloMapView.inset(14, 14));
        assertEquals(0, JelloMapView.inset(14, 20));
    }

    @Test
    void theCutGrowsSmoothlyTowardsTheEdge() {
        int last = 0;
        for (double depth = 14; depth >= 0; depth -= 0.5) {
            int inset = JelloMapView.inset(14, depth);
            assertTrue(inset >= last, "depth " + depth);
            last = inset;
        }

        // Never more than the radius, even right on the edge.
        assertTrue(JelloMapView.inset(14, 0) <= 14);
    }
}
