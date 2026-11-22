package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mentalfrostbyte.jello.module.impl.combat.VisiblePointScan.Kind;
import com.mentalfrostbyte.jello.module.impl.combat.VisiblePointScan.Pick;
import com.mentalfrostbyte.jello.setting.EnumSetting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisiblePointScanTest {
    private static final Vec3 EYE = new Vec3(-0.30, 1.62, 0);
    private static final AABB DUMMY = new AABB(-0.06, 0, 2.55, 0.54, 1.8, 3.15);
    private static final AABB PARTIAL_COVER = new AABB(0, 1, 1, 1, 2, 2);
    private static final Pick HIT = new Pick(Kind.TARGET, "2", 2.6);
    private static final Pick BLOCK = new Pick(Kind.BLOCK, "minecraft:stone", 1.6);

    private static Pick trace(Vec3 point, AABB cover) {
        Vec3 end = EYE.add(point.subtract(EYE).normalize().scale(3));
        double entity = DUMMY.clip(EYE, end).map(EYE::distanceToSqr).orElse(Double.POSITIVE_INFINITY);
        double block = cover.clip(EYE, end).map(EYE::distanceToSqr).orElse(Double.POSITIVE_INFINITY);
        if (block < Double.POSITIVE_INFINITY && block <= entity) return BLOCK;
        return Double.isFinite(entity) ? HIT : new Pick(Kind.MISS, "none", 3);
    }

    @Test void aBlockedCenterAndBlockedStartingViewStillFindTheExposedEdge() {
        Function<Vec3, Pick> cast = point -> trace(point, PARTIAL_COVER);
        var result = new VisiblePointScan().find(EYE, DUMMY, BLOCK, cast);
        assertEquals(Kind.BLOCK, result.center().kind());
        assertEquals("surface", result.source());
        assertEquals(Kind.TARGET, cast.apply(result.point()).kind());
        assertTrue(result.samples() <= VisiblePointScan.BUDGET);
    }

    @Test void manualViewOnTheEdgeIsKeptEvenWhenTheCenterIsBlocked() {
        var result = new VisiblePointScan().find(EYE, DUMMY, HIT, point -> trace(point, PARTIAL_COVER));
        assertEquals("camera", result.source());
        assertNull(result.point());
        assertEquals(Kind.BLOCK, result.center().kind());
        assertEquals(0, result.samples());
    }

    @Test void rememberedPointMovesWithTheDummyAndMustPassAFreshPick() {
        var scan = new VisiblePointScan();
        Vec3 edge = new Vec3(-0.02, 1.4, 2.55);
        scan.remember(DUMMY, edge);
        AABB moved = DUMMY.move(1, 0, 0);
        Vec3 expected = edge.add(1, 0, 0);
        var result = scan.find(EYE, moved, BLOCK, point -> point.distanceToSqr(expected) < 1e-12 ? HIT : BLOCK);
        assertEquals("previous", result.source());
        assertEquals(0, result.samples());
        assertEquals("no_sample", scan.find(EYE, moved, BLOCK, point -> BLOCK).source());
    }

    @Test void fullCoverAndAnInterveningEntityBothLeaveTheCameraAlone() {
        AABB cover = new AABB(-1, 0, 1, 1, 2, 2);
        var scan = new VisiblePointScan();
        var blocked = scan.find(EYE, DUMMY, BLOCK, point -> trace(point, cover));
        assertEquals("no_sample", blocked.source());
        assertNull(blocked.point());
        Pick other = new Pick(Kind.OTHER_ENTITY, "3", 1.5);
        var intercepted = scan.find(EYE, DUMMY, other, point -> other);
        assertNull(intercepted.point());
        assertEquals(Kind.OTHER_ENTITY, intercepted.center().kind());
        assertEquals(2 * VisiblePointScan.BUDGET, intercepted.nextSample());
        scan.reset();
        assertEquals(blocked.nextSample(), scan.find(EYE, DUMMY, BLOCK, point -> BLOCK).nextSample());
    }

    @Test void laterTicksVisitNewPointsWithinTheSamePerTickBudget() {
        var scan = new VisiblePointScan();
        // A window on the near Z face at one quarter height and width.
        Function<Vec3, Pick> narrow = point -> Math.abs(point.x - 0.09) < 0.001
                && Math.abs(point.y - 0.45) < 0.001 && point.z < 2.56 ? HIT : BLOCK;
        var result = scan.find(EYE, DUMMY, BLOCK, narrow);
        assertEquals("no_sample", result.source());
        for (int tick = 0; tick < 11 && result.point() == null; tick++) {
            result = scan.find(EYE, DUMMY, BLOCK, narrow);
            assertTrue(result.samples() <= VisiblePointScan.BUDGET);
        }
        assertEquals("surface", result.source());
    }

    @Test void aNativeVetoOfTheCenterDoesNotStarveSurfaceTesting() {
        var scan = new VisiblePointScan();
        assertEquals("center", scan.find(EYE, DUMMY, BLOCK, point -> HIT).source());
        scan.rejected();
        assertEquals("surface", scan.find(EYE, DUMMY, BLOCK, point -> HIT).source());
    }

    @Test void logFlushesManualInputAndCreatesAnotherFileForTheNextSession(@TempDir Path directory) throws Exception {
        var log = new RotationTestLog();
        Path first = log.open(directory);
        var input = RotationTestLog.event("manual_click", 7);
        input.addProperty("click", 1);
        log.write(input, true);
        assertEquals(7, JsonParser.parseString(Files.readAllLines(first).get(1)).getAsJsonObject().get("tick").getAsInt());
        log.write(RotationTestLog.event("end", 7), false);
        log.close();
        try {
            assertNotEquals(first, log.open(directory));
            List<String> events = Files.readAllLines(first).stream()
                    .map(line -> JsonParser.parseString(line).getAsJsonObject().get("event").getAsString()).toList();
            assertEquals(List.of("start", "manual_click", "end"), events);
        } finally {
            log.close();
        }
    }

    @Test void theModeRoundTripsThroughTheUsualRotationSetting() {
        KillAura aura = new KillAura();
        var rotation = (EnumSetting<?>) aura.setting("Rotation").orElseThrow();
        assertEquals(KillAura.RotationMode.SNAP, rotation.get());
        assertTrue(rotation.fromJson(JsonParser.parseString("\"VISIBLE_TEST\"")));
        assertEquals("Visible Test", EnumSetting.label(rotation.get()));
        assertFalse(aura.setting("Silent").orElseThrow().isVisible());
        assertTrue(aura.setting("Timing").orElseThrow().isVisible());
        assertTrue(aura.setting("Min CPS").orElseThrow().isVisible());
        rotation.reset();
        assertTrue(aura.setting("Silent").orElseThrow().isVisible());
        assertTrue(aura.setting("Timing").orElseThrow().isVisible());
    }

    @Test void theNamedRemoteDummyBelongsToThePracticeArena() {
        assertTrue(VisibleRotationTest.namedDummy("Aim2", false, "as.test.ccbluex.net"));
        assertTrue(VisibleRotationTest.namedDummy("Aim2", false, "TEST.CCBLUEX.NET.:25565"));
        assertTrue(VisibleRotationTest.namedDummy("Aim2", true, null));
        assertFalse(VisibleRotationTest.namedDummy("Aim3", false, "as.test.ccbluex.net"));
        assertFalse(VisibleRotationTest.namedDummy("Aim2", false, "as.test.ccbluex.net.example.org"));
        assertFalse(VisibleRotationTest.namedDummy("Aim2", false, null));
    }
}
