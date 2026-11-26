package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModernFreecamFpvTest {
    private static final float EPS = 1.0E-4F;

    @Test
    void compassAndCardinalsMatchMinecraftAxes() {
        assertEquals(0.0F, ModernFreecamFpv.compassDegrees(-180.0F), EPS);
        assertEquals(0.0F, ModernFreecamFpv.compassDegrees(180.0F), EPS);
        assertEquals(90.0F, ModernFreecamFpv.compassDegrees(-90.0F), EPS);
        assertEquals(180.0F, ModernFreecamFpv.compassDegrees(0.0F), EPS);
        assertEquals(270.0F, ModernFreecamFpv.compassDegrees(90.0F), EPS);

        assertEquals("N", ModernFreecamFpv.cardinalLabel(0));
        assertEquals("N", ModernFreecamFpv.cardinalLabel(359));
        assertEquals("NE", ModernFreecamFpv.cardinalLabel(45));
        assertEquals("E", ModernFreecamFpv.cardinalLabel(90));
        assertEquals("SE", ModernFreecamFpv.cardinalLabel(135));
        assertEquals("S", ModernFreecamFpv.cardinalLabel(180));
        assertEquals("SW", ModernFreecamFpv.cardinalLabel(225));
        assertEquals("W", ModernFreecamFpv.cardinalLabel(270));
        assertEquals("NW", ModernFreecamFpv.cardinalLabel(315));
    }

    @Test
    void shortestDeltaAndBearingWrapAcrossNorthSeam() {
        assertEquals(-5.0F, ModernFreecamFpv.shortestAngleDelta(0.0F, 355.0F), EPS);
        assertEquals(10.0F, ModernFreecamFpv.shortestAngleDelta(355.0F, 5.0F), EPS);

        assertEquals(0.0F, ModernFreecamFpv.bearingDegrees(0.0, 0.0, 0.0, -25.0), EPS);
        assertEquals(90.0F, ModernFreecamFpv.bearingDegrees(0.0, 0.0, 25.0, 0.0), EPS);
        assertEquals(180.0F, ModernFreecamFpv.bearingDegrees(0.0, 0.0, 0.0, 25.0), EPS);
        assertEquals(270.0F, ModernFreecamFpv.bearingDegrees(0.0, 0.0, -25.0, 0.0), EPS);
        assertEquals(45.0F, ModernFreecamFpv.bearingDegrees(10.0, 10.0, 20.0, 0.0), EPS);
    }
}
