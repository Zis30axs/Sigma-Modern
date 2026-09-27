package com.mentalfrostbyte.jello.util.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import java.util.Optional;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RotationsTest {

    private static final Vec3 EYE = new Vec3(0.0, 1.62, 0.0);

    @Test
    void facingFollowsVanillasCompass() {
        assertEquals(0.0F, Rotations.towards(EYE, EYE.add(0, 0, 1)).yaw(), 1e-4, "south, +z, is yaw 0");
        assertEquals(90.0F, Rotations.towards(EYE, EYE.add(-1, 0, 0)).yaw(), 1e-4, "west, -x, is 90");
        assertEquals(-90.0F, Rotations.towards(EYE, EYE.add(1, 0, 0)).yaw(), 1e-4, "east, +x, is -90");
        assertEquals(180.0F, Math.abs(Rotations.towards(EYE, EYE.add(0, 0, -1)).yaw()), 1e-4, "north is the seam");
        assertEquals(-45.0F, Rotations.towards(EYE, EYE.add(0, 1, 1)).pitch(), 1e-4, "up is negative pitch");
    }

    @Test
    void aContinuousYawTakesTheShortWayRound() {
        assertEquals(185.0F, Rotations.continuous(170.0F, -175.0F), 1e-4);
        assertEquals(895.0F, Rotations.continuous(900.0F, 175.0F), 1e-4, "a player who turned twice round stays there");
        assertEquals(-10.0F, Rotations.continuous(0.0F, 350.0F), 1e-4);
    }

    @Test
    void limitCapsEachAxis() {
        Rotation step = Rotations.limit(new Rotation(0, 0), new Rotation(100, -50), 40, 30);
        assertEquals(new Rotation(40, -30), step);
        assertEquals(new Rotation(5, 5), Rotations.limit(new Rotation(0, 0), new Rotation(5, 5), 40, 30));
    }

    @Test
    void mouseStepsAreWhatVanillaTurnsBy() {
        // MouseHandler: f = s * 0.6 + 0.2; a pixel turns f^3 * 8 * 0.15 degrees.
        assertEquals(0.15, Rotations.mouseStep(0.5), 1e-9, "the default sensitivity");
        assertEquals(0.0096, Rotations.mouseStep(0.0), 1e-9);

        Rotation turned = Rotations.quantize(new Rotation(10, 0), new Rotation(11, 0.07F), 0.15);
        assertEquals(10.0 + 7 * 0.15, turned.yaw(), 1e-4, "a whole number of steps");
        assertEquals(0.0F, turned.pitch(), 1e-6, "less than half a step is no step");
        assertEquals(90.0F, Rotations.quantize(new Rotation(0, 89), new Rotation(0, 95), 0.15).pitch(), "never past straight down");
    }

    @Test
    void theNearestPointIsOnTheShrunkBox() {
        AABB box = new AABB(1.0, 0.0, -0.3, 1.6, 1.95, 0.3);
        Vec3 point = Rotations.nearestPoint(EYE, box, 0.15);
        assertEquals(1.15, point.x, 1e-9);
        assertEquals(1.62, point.y, 1e-9, "level with the eye when the box spans it");
        assertEquals(0.0, point.z, 1e-9);
        assertEquals(box.getCenter(), Rotations.nearestPoint(new Vec3(1.3, 5.0, 0.0), box, 0.15), "right above: the centre");
    }

    @Test
    void aLookHitsWhatItPointsAt() {
        AABB box = new AABB(1.0, 0.0, -0.3, 1.6, 1.95, 0.3);
        Optional<Vec3> hit = Rotations.hit(EYE, new Rotation(-90, 0), box, 3.0);
        assertTrue(hit.isPresent());
        assertEquals(1.0, hit.get().x, 1e-9);
        assertFalse(Rotations.hit(EYE, new Rotation(0, 0), box, 3.0).isPresent(), "looking south misses a box to the east");
        assertFalse(Rotations.hit(EYE, new Rotation(-90, 0), box, 0.9).isPresent(), "out of reach");
    }
}
