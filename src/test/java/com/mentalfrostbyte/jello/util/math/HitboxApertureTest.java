package com.mentalfrostbyte.jello.util.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.util.math.HitboxAperture.Selection;
import com.mentalfrostbyte.jello.util.math.HitboxAperture.Zone;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HitboxApertureTest {

    private static final Vec3 EYE = new Vec3(0.5, 1.62, 0.5);
    private static final Rotation SOUTH = new Rotation(0.0F, 0.0F);

    @Test
    void threadsDiagonalStairAndBottomSlabNotchWhereCenterAndNearestFail() {
        // Enemy standing at z = 2.7..3.3, y = 0..1.8. Between z = 1.0..2.0 sits:
        // - an upside-down stair (top slab y = 1.5..2.0 across z = 1..2, back step y = 1.0..1.5 across z = 1.5..2.0)
        // - a bottom slab (y = 0.0..0.5 across z = 1.0..2.0)
        AABB target = new AABB(0.2, 0.0, 2.7, 0.8, 1.8, 3.3);
        HitboxAperture.Scanner scanner = new HitboxAperture.Scanner();
        scanner.addObstacle(0.0, 1.5, 1.0, 1.0, 2.0, 2.0);
        scanner.addObstacle(0.0, 1.0, 1.5, 1.0, 1.5, 2.0);
        scanner.addObstacle(0.0, 0.0, 1.0, 1.0, 0.5, 2.0);

        Rotation centerAim = Rotations.towards(EYE, target.getCenter());
        Rotation nearestAim = Rotations.towards(EYE, Rotations.nearestPoint(EYE, target, 0.15));
        assertFalse(scanner.verifiesRotation(EYE, centerAim, target, 3.0), "center ray hits the stair's inner step");
        assertFalse(scanner.verifiesRotation(EYE, nearestAim, target, 3.0), "nearestPoint ray hits the stair's top half");

        Selection found = scanner.selectFromObstacles(EYE, SOUTH, target, 3.0, 4.0, Zone.BEST, null, false);
        assertNotNull(found, "diagonal aperture through the stair notch reaches the lower legs");
        Rotation solved = Rotations.towards(EYE, found.point());
        assertTrue(scanner.verifiesRotation(EYE, solved, target, 3.0), "selected look hits target within 3.0m without clipping");
    }

    @Test
    void detectsOneSixteenthVoxelSlitAndCentersInsideIt() {
        // A 1/16 block (0.0625m) slit at y = 1.50..1.5625 across a 3/16-thick barrier z = 1.0..1.1875,
        // where both getCenter() (y = 0.9) and nearestPoint (y = 1.62) are blocked.
        AABB target = new AABB(0.2, 0.0, 2.5, 0.8, 1.8, 3.1);
        HitboxAperture.Scanner scanner = new HitboxAperture.Scanner();
        scanner.addObstacle(0.0, 0.0, 1.0, 1.0, 1.50, 1.1875);
        scanner.addObstacle(0.0, 1.5625, 1.0, 1.0, 2.5, 1.1875);

        assertFalse(scanner.verifiesRotation(EYE, Rotations.towards(EYE, target.getCenter()), target, 3.0));
        assertFalse(scanner.verifiesRotation(EYE, Rotations.towards(EYE, Rotations.nearestPoint(EYE, target, 0.15)), target, 3.0));

        Selection found = scanner.selectFromObstacles(EYE, SOUTH, target, 3.0, 4.0, Zone.BEST, null, false);
        assertNotNull(found, "0.04m surface raster catches a 0.0625m 1/16-block slit");
        assertTrue(scanner.verifiesRotation(EYE, Rotations.towards(EYE, found.point()), target, 3.0));
    }

    @Test
    void headBodyAndFeetHonorPreferredBandsThenSoftFallbackWhenBlocked() {
        AABB target = new AABB(0.2, 0.0, 2.5, 0.8, 1.8, 3.1);
        HitboxAperture.Scanner scanner = new HitboxAperture.Scanner();

        Selection openHead = scanner.selectFromObstacles(EYE, SOUTH, target, 3.0, 4.0, Zone.HEAD, null, false);
        Selection openBody = scanner.selectFromObstacles(EYE, SOUTH, target, 3.0, 4.0, Zone.BODY, null, false);
        Selection openFeet = scanner.selectFromObstacles(EYE, SOUTH, target, 3.0, 4.0, Zone.FEET, null, false);
        assertNotNull(openHead);
        assertNotNull(openBody);
        assertNotNull(openFeet);
        assertTrue(openHead.inPreferredZone() && openHead.point().y > 1.35);
        assertTrue(openBody.inPreferredZone() && openBody.point().y > 0.7 && openBody.point().y < 1.1);
        assertTrue(openFeet.inPreferredZone() && openFeet.point().y < 0.45);

        // Overhanging stair + upper wall blocks Head and Body completely, leaving ankles y = 0.0..0.5 exposed under z = 2.0..2.5.
        scanner.addObstacle(0.0, 1.0, 1.5, 1.0, 2.5, 2.5);
        scanner.addObstacle(0.0, 0.5, 2.0, 1.0, 1.0, 2.5);
        Selection fallbackFeet = scanner.selectFromObstacles(EYE, SOUTH, target, 3.0, 4.0, Zone.HEAD, null, false);
        assertNotNull(fallbackFeet, "Head falls back to exposed ankles rather than stalling");
        assertFalse(fallbackFeet.inPreferredZone());
        assertTrue(fallbackFeet.point().y < 0.5);
        assertTrue(scanner.verifiesRotation(EYE, Rotations.towards(EYE, fallbackFeet.point()), target, 3.0));
    }

    @Test
    void highLedgeFallsBackFromOutOfReachHeadToInReachFeet() {
        // Enemy stands 1.6m higher on a ledge at z = 2.6..3.2: head (y ~ 3.13) is > 3.0m away, feet (y ~ 1.62) is 2.1m away.
        AABB ledgeTarget = new AABB(0.2, 1.6, 2.6, 0.8, 3.4, 3.2);
        HitboxAperture.Scanner scanner = new HitboxAperture.Scanner();

        Selection chosen = scanner.selectFromObstacles(EYE, SOUTH, ledgeTarget, 3.0, 4.0, Zone.HEAD, null, false);
        assertNotNull(chosen);
        assertFalse(chosen.inPreferredZone(), "Head band is > 3.0m away, so solver drops down into 3.0m attack reach");
        assertTrue(Rotations.hit(EYE, Rotations.towards(EYE, chosen.point()), ledgeTarget, 3.0).isPresent(),
                "fallback point lands immediately inside the 3.0m attack reach");
    }

    @Test
    void returnsNullWhenCompletelyWallOccluded() {
        AABB target = new AABB(0.2, 0.0, 2.5, 0.8, 1.8, 3.1);
        HitboxAperture.Scanner scanner = new HitboxAperture.Scanner();
        scanner.addObstacle(-1.0, -1.0, 1.0, 2.0, 3.0, 2.0);

        assertNull(scanner.selectFromObstacles(EYE, SOUTH, target, 3.0, 4.0, Zone.BEST, null, false));
        assertNotNull(scanner.selectFromObstacles(EYE, SOUTH, target, 3.0, 4.0, Zone.BEST, null, true),
                "Through Walls bypasses block obstacles");
    }

    @Test
    void refineQuantizedRescuesSubStepSlitInWholeMouseSteps() {
        double step = 0.15; // Default 100% mouse sensitivity step
        Rotation from = new Rotation(0.0F, 0.0F);
        // Ideal pitch sits at 1.074 deg -> Math.round(1.074 / 0.15) = 7 steps (1.05 deg), which clips the upper lip;
        // 8 whole steps (1.20 deg) threads the slit cleanly.
        Rotation ideal = new Rotation(0.0F, 1.074F);
        Rotation rounded = Rotations.quantize(from, ideal, step);
        assertEquals(1.05F, rounded.pitch(), 1e-4);

        Rotation rescued = HitboxAperture.refineQuantized(from, ideal, rounded, step,
                rot -> rot.pitch() >= 1.07F && rot.pitch() <= 1.25F && Math.abs(rot.yaw()) < 0.05F);
        assertEquals(1.20F, rescued.pitch(), 1e-4, "steps up to 8 whole mouse steps where the slit is open");
        double pitchSteps = (rescued.pitch() - from.pitch()) / step;
        assertEquals(Math.round(pitchSteps), pitchSteps, 1e-4, "remains an exact integer multiple of mouseStep");
    }
}
