package com.mentalfrostbyte.jello.module.impl.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SpeedTest {

    private static Input keys(final boolean forward, final boolean backward, final boolean left, final boolean right) {
        return new Input(forward, backward, left, right, false, false, false);
    }

    @Test
    void theKeysPointWhereVanillaWouldWalk() {
        assertEquals(30.0F, Speed.movementYaw(30.0F, keys(true, false, false, false)));
        assertEquals(210.0F, Speed.movementYaw(30.0F, keys(false, true, false, false)));
        assertEquals(-60.0F, Speed.movementYaw(30.0F, keys(false, false, true, false)));
        assertEquals(120.0F, Speed.movementYaw(30.0F, keys(false, false, false, true)));
        assertEquals(-15.0F, Speed.movementYaw(30.0F, keys(true, false, true, false)), "forward-left is half way");
        assertEquals(255.0F, Speed.movementYaw(30.0F, keys(false, true, true, false)), "back-left mirrors it");
        assertEquals(30.0F, Speed.movementYaw(30.0F, keys(true, true, true, true)), "opposite keys cancel out");
    }

    @Test
    void strafingSetsTheHorizontalSpeedAndKeepsTheVertical() {
        Vec3 result = Speed.withStrafe(new Vec3(0.05, 0.42, -0.3), 0.4, 0.0F, keys(true, false, false, false));
        assertEquals(0.0, result.x, 1e-9);
        assertEquals(0.42, result.y, 1e-9);
        assertEquals(0.4, result.z, 1e-9, "yaw 0 faces +z");
        assertEquals(0.4, result.horizontalDistance(), 1e-9);

        Vec3 west = Speed.withStrafe(Vec3.ZERO, 0.4, 90.0F, keys(true, false, false, false));
        assertEquals(-0.4, west.x, 1e-9, "yaw 90 faces -x");
    }

    @Test
    void noKeysMeansNoHorizontalSpeed() {
        Vec3 result = Speed.withStrafe(new Vec3(0.3, 0.1, 0.3), 0.4, 0.0F, keys(false, false, false, false));
        assertEquals(new Vec3(0.0, 0.1, 0.0), result);
        assertFalse(Speed.isMoving(keys(true, true, false, false)));
        assertTrue(Speed.isMoving(keys(false, false, false, true)));
    }
}
