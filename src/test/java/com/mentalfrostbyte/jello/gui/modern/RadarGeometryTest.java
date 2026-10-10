package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RadarGeometryTest {
    @Test void usesMinecraftYawAndKeepsRightOnTheRight() {
        RadarGeometry.Relative front = RadarGeometry.relative(new Vec3(0, 3, 10), 0F);
        assertNotNull(front);
        assertEquals(0.0, front.right(), 1.0E-8);
        assertEquals(10.0, front.forward(), 1.0E-8);
        assertEquals(10.0, front.distance(), 1.0E-8);
        assertEquals(3.0, front.elevation(), 1.0E-8);
        assertEquals(90.0, RadarGeometry.relative(new Vec3(-10, 0, 0), 0F).bearingDegrees(), 1.0E-8);
        assertEquals(-90.0, RadarGeometry.relative(new Vec3(10, 0, 0), 0F).bearingDegrees(), 1.0E-8);
        assertEquals(0.0, RadarGeometry.relative(new Vec3(-10, 0, 0), 90F).bearingDegrees(), 1.0E-8);
        assertEquals(90.0, RadarGeometry.relative(new Vec3(0, 0, -10), 90F).bearingDegrees(), 1.0E-8);
    }

    @Test void yawWrapNeverFlipsAFrontContactBehind() {
        Vec3 north = new Vec3(0, 0, -30);
        assertEquals(0.0, RadarGeometry.relative(north, 180F).bearingDegrees(), 1.0E-8);
        assertEquals(0.0, RadarGeometry.relative(north, -180F).bearingDegrees(), 1.0E-8);
        assertEquals(0.0, RadarGeometry.relative(north, 540F).bearingDegrees(), 1.0E-8);
        assertEquals(1.0, RadarGeometry.relative(north, 179F).bearingDegrees(), 1.0E-8);
        assertEquals(-1.0, RadarGeometry.relative(north, -179F).bearingDegrees(), 1.0E-8);
    }

    @Test void rejectsRearAndOutOfRangeContactsRatherThanClamping() {
        assertTrue(RadarGeometry.withinScan(RadarGeometry.relative(new Vec3(0, 0, 64), 0F), 64, 120));
        assertFalse(RadarGeometry.withinScan(RadarGeometry.relative(new Vec3(0, 0, 64.01), 0F), 64, 120));
        assertFalse(RadarGeometry.withinScan(RadarGeometry.relative(new Vec3(0, 0, -10), 0F), 64, 180));
        assertFalse(RadarGeometry.withinScan(RadarGeometry.relative(new Vec3(-10, 0, 1), 0F), 64, 120));
        assertFalse(RadarGeometry.withinScan(RadarGeometry.relative(new Vec3(0, 30, 0), 0F), 64, 120));
        assertFalse(RadarGeometry.withinScan(null, 64, 120));
    }

    @Test void sectorIsTheFullWidthAndHasAnInclusiveBoundary() {
        Vec3 edge = new Vec3(-Math.sin(Math.toRadians(15)) * 20, 0, Math.cos(Math.toRadians(15)) * 20);
        assertTrue(RadarGeometry.withinScan(RadarGeometry.relative(edge, 0F), 64, 30));
        assertFalse(RadarGeometry.withinScan(RadarGeometry.relative(edge, -0.1F), 64, 30));
        assertTrue(RadarGeometry.withinScan(RadarGeometry.relative(new Vec3(0, 100, 10), 0F), 16, 30));
    }

    @Test void closureUsesRelativeVelocityAndPreservesItsSign() {
        Vec3 offset = new Vec3(0, 0, 10);
        assertEquals(0.3, RadarGeometry.closure(offset, new Vec3(0, 0, -0.3)), 1.0E-8);
        assertEquals(-0.3, RadarGeometry.closure(offset, new Vec3(0, 0, 0.3)), 1.0E-8);
        assertEquals(0.0, RadarGeometry.closure(offset, new Vec3(0.3, 0, 0)), 1.0E-8);
        assertEquals(0.0, RadarGeometry.closure(Vec3.ZERO, new Vec3(1, 2, 3)), 1.0E-8);
        assertEquals(1.0, RadarGeometry.closure(new Vec3(0, 5, 0), new Vec3(0, -1, 0)), 1.0E-8);
    }

    @Test void headOnRequiresTheTargetToApproachAndTheGapToClose() {
        Vec3 offset = new Vec3(0, 0, 10);
        assertTrue(RadarGeometry.isHeadOn(offset, new Vec3(0, 0, -0.2), Vec3.ZERO));
        assertTrue(RadarGeometry.isHeadOn(offset, new Vec3(0, 0, -0.2), new Vec3(0, 0, 0.2)));
        assertFalse(RadarGeometry.isHeadOn(offset, Vec3.ZERO, new Vec3(0, 0, 0.2)));
        assertFalse(RadarGeometry.isHeadOn(offset, new Vec3(0, 0, 0.1), new Vec3(0, 0, 0.2)));
        assertFalse(RadarGeometry.isHeadOn(offset, new Vec3(0, 0, -0.2), new Vec3(0, 0, -0.4)));
        assertFalse(RadarGeometry.isHeadOn(offset, new Vec3(0.2, 0, 0), Vec3.ZERO));
    }

    @Test void invalidValuesCannotProduceVisibleContactsOrThreats() {
        Vec3 invalid = new Vec3(Double.NaN, 0, 1);
        assertNull(RadarGeometry.relative(invalid, 0));
        assertNull(RadarGeometry.relative(new Vec3(0, Double.POSITIVE_INFINITY, 1), 0));
        assertNull(RadarGeometry.relative(new Vec3(0, 0, 1), Float.NaN));
        assertFalse(RadarGeometry.withinScan(RadarGeometry.relative(new Vec3(0, 0, 1), 0), Double.NaN, 120));
        assertFalse(RadarGeometry.withinScan(RadarGeometry.relative(new Vec3(0, 0, 1), 0), 64, 360));
        assertFalse(RadarGeometry.withinScan(new RadarGeometry.Relative(0, 1, Double.NaN, 0, 0), 64, 120));
        assertEquals(0.0, RadarGeometry.closure(invalid, Vec3.ZERO));
        assertFalse(RadarGeometry.isHeadOn(new Vec3(0, 0, 10), invalid, Vec3.ZERO));
    }
}
