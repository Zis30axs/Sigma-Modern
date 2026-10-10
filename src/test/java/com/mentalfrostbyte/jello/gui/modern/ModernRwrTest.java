package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.function.ToDoubleFunction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ModernRwrTest {
    @Test void cardinalBearingsMatchTheMinecraftHeadingFrame() {
        assertPosition(new Vec3(0, 0, 10), 0, 84, 42);
        assertPosition(new Vec3(0, 0, -10), 0, 84, 140);
        assertPosition(new Vec3(-10, 0, 0), 0, 133, 91);
        assertPosition(new Vec3(10, 0, 0), 0, 35, 91);
        assertPosition(new Vec3(-10, 0, 0), 90, 84, 42);
        assertPosition(new Vec3(10, 0, 0), 90, 84, 140);
        assertPosition(new Vec3(0, 0, -10), 90, 133, 91);
        assertPosition(new Vec3(0, 0, 10), 90, 35, 91);
    }

    @Test void verticalIncomingThreatsKeepSeparateUpAndDownLanesAtEveryHeading() {
        var up = threat(1, new Vec3(0, 8, 0), RwrThreats.Type.ARROW, 0.5);
        var down = threat(2, new Vec3(0, -8, 0), RwrThreats.Type.TRIDENT, 0.4);
        for (float yaw : new float[]{0, 90, 180, -90}) {
            var plots = ModernRwr.plots(List.of(up, down), yaw);
            assertEquals(2, plots.size());
            var upper = plots.stream().filter(plot -> plot.threat() == up).findFirst().orElseThrow();
            var lower = plots.stream().filter(plot -> plot.threat() == down).findFirst().orElseThrow();
            assertEquals(ModernRwr.Vertical.UP, upper.vertical());
            assertEquals(ModernRwr.Vertical.DOWN, lower.vertical());
            assertEquals(upper.y(), lower.y());
            assertTrue(upper.y() > 155, "Vertical labels belong outside the bearing circle");
            assertTrue(lower.x() - upper.x() >= 60, "UP and DN labels have independent space");
        }
    }

    @Test void verticalStackingShowsTheSoonestIncomingThreatInsteadOfPassiveContacts() {
        var passive = threat(1, new Vec3(0, 4, 0), RwrThreats.Type.CONTACT, Double.POSITIVE_INFINITY);
        var aim = threat(2, new Vec3(0, 6, 0), RwrThreats.Type.AIM, Double.POSITIVE_INFINITY);
        var later = threat(3, new Vec3(0, 8, 0), RwrThreats.Type.ARROW, 1.5);
        var imminent = threat(4, new Vec3(0, 9, 0), RwrThreats.Type.TRIDENT, 0.2);
        var plots = ModernRwr.plots(List.of(passive, aim, later, imminent), 0);
        assertEquals(1, plots.size());
        assertSame(imminent, plots.getFirst().threat());
        assertEquals(ModernRwr.Vertical.UP, plots.getFirst().vertical());
    }

    @Test void incomingWinsOverAnOverlappingContactEvenWhenTheInputOrderIsReversed() {
        var passive = threat(1, new Vec3(-5, 0, 0), RwrThreats.Type.CONTACT, Double.POSITIVE_INFINITY);
        var aim = threat(2, new Vec3(-8, 0, 0), RwrThreats.Type.AIM, Double.POSITIVE_INFINITY);
        var arrow = threat(3, new Vec3(-10, 0, 0), RwrThreats.Type.ARROW, 1);
        var trident = threat(4, new Vec3(-20, 0, 0), RwrThreats.Type.TRIDENT, 0.2);
        var plots = ModernRwr.plots(List.of(passive, aim, arrow, trident), 0);
        assertEquals(1, plots.size());
        assertSame(trident, plots.getFirst().threat());
        assertEquals(111, plots.getFirst().x(), 1.0E-5);
        assertEquals(91, plots.getFirst().y(), 1.0E-5);
    }

    @Test void declutteringNeverChangesTheDirectionOfAnySurvivingBearing() {
        var threats = List.of(
                threat(1, new Vec3(1, 0, 10), RwrThreats.Type.CONTACT, Double.POSITIVE_INFINITY),
                threat(2, new Vec3(1.1, 0, 10), RwrThreats.Type.CONTACT, Double.POSITIVE_INFINITY),
                threat(3, new Vec3(-5, 0, -2), RwrThreats.Type.AIM, Double.POSITIVE_INFINITY),
                threat(4, new Vec3(8, 0, -5), RwrThreats.Type.ARROW, 0.4),
                threat(5, new Vec3(8.1, 0, -5), RwrThreats.Type.TRIDENT, 0.8));
        for (float yaw : new float[]{0, 37, 90, 179, -90}) {
            var plots = ModernRwr.plots(threats, yaw);
            assertTrue(plots.size() < threats.size());
            for (var plot : plots) {
                var expected = RadarGeometry.relative(plot.threat().offset(), yaw);
                assertNotNull(expected);
                double actual = Math.toDegrees(Math.atan2(plot.x() - 84, 91 - plot.y()));
                assertEquals(0, Math.IEEEremainder(actual - expected.bearingDegrees(), 360), 1.0E-4);
                assertEquals(ModernRwr.Vertical.NONE, plot.vertical());
            }
        }
    }

    @Test void invalidBearingsDoNotBecomeOrdinaryForwardContacts() {
        assertTrue(ModernRwr.plots(List.of(threat(1, new Vec3(Double.NaN, 0, 0),
                RwrThreats.Type.ARROW, 0.2)), 0).isEmpty());
        assertTrue(ModernRwr.plots(List.of(threat(1, new Vec3(0, 1, 2),
                RwrThreats.Type.ARROW, 0.2)), Float.NaN).isEmpty());
    }

    @Test void footerReservesAnActualGapForTheRightColumnWithEitherFontWidth() {
        // Narrow instrument lettering and wider Modern lettering exercise both full and compact left labels.
        for (double characterWidth : new double[]{4.8, 8.5}) {
            ToDoubleFunction<String> measure = text -> text.length() * characterWidth;
            for (int visible : new int[]{0, 1, 10, 12}) {
                var footer = ModernRwr.footer(12, visible, false, 1.8, 128, measure);
                assertEquals("1.8s", footer.right());
                assertTrue(measure.applyAsDouble(footer.left()) <= footer.leftBudget());
                assertEquals(146, footer.leftBudget() + measure.applyAsDouble(footer.right()) + 10, 1.0E-5);
                assertFalse(footer.left().contains("STACKED"));
                if (visible < 12) assertTrue(footer.left().contains("+" + (12 - visible)));
            }
        }
    }

    @Test void footerKeepsRangeInPreviewAndUsesShortContactCountWithoutHiddenLabels() {
        ToDoubleFunction<String> measure = text -> text.length() * 5;
        var preview = ModernRwr.footer(2, 2, true, 0.2, 64, measure);
        assertEquals("2 CONTACTS", preview.left());
        assertEquals("64 m", preview.right());
        var passive = ModernRwr.footer(12, 8, false, Double.POSITIVE_INFINITY, 128, measure);
        assertEquals("8 VISIBLE / +4", passive.left());
        assertEquals("128 m", passive.right());
    }

    private static void assertPosition(Vec3 offset, float yaw, float x, float y) {
        var plot = ModernRwr.plots(List.of(threat(1, offset, RwrThreats.Type.CONTACT, Double.POSITIVE_INFINITY)), yaw).getFirst();
        assertEquals(x, plot.x(), 1.0E-5);
        assertEquals(y, plot.y(), 1.0E-5);
        assertEquals(ModernRwr.Vertical.NONE, plot.vertical());
    }

    private static RwrThreats.Threat threat(int id, Vec3 offset, RwrThreats.Type type, double eta) {
        return new RwrThreats.Threat(id, offset, offset.length(), type, eta);
    }
}
