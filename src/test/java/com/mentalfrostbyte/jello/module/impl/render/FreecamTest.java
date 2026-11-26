package com.mentalfrostbyte.jello.module.impl.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import com.mentalfrostbyte.jello.module.ModuleManager;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FreecamTest {
    private static final double EPS = 1.0E-5;

    private static Input keys(
            final boolean forward, final boolean backward,
            final boolean left, final boolean right,
            final boolean jump, final boolean shift, final boolean sprint) {
        return new Input(forward, backward, left, right, jump, shift, sprint);
    }

    @Test
    void cardinalAndDiagonalFlightVelocityFollowsMinecraftYawAndNormalizesDiagonal() {
        // Yaw 0 faces +Z (south), left (+strafe) is +X (east).
        Vec3 south = Freecam.computeTargetVelocity(keys(true, false, false, false, false, false, false), 0.0F, 1.5, 0.8, 1.8);
        assertEquals(0.0, south.x, EPS);
        assertEquals(0.0, south.y, EPS);
        assertEquals(1.5, south.z, EPS);

        Vec3 eastStrafe = Freecam.computeTargetVelocity(keys(false, false, true, false, false, false, false), 0.0F, 1.5, 0.8, 1.8);
        assertEquals(1.5, eastStrafe.x, EPS);
        assertEquals(0.0, eastStrafe.z, EPS);

        // Yaw 90 faces -X (west); Yaw 180 faces -Z (north); Yaw -90 faces +X (east).
        Vec3 west = Freecam.computeTargetVelocity(keys(true, false, false, false, false, false, false), 90.0F, 1.5, 0.8, 1.8);
        assertEquals(-1.5, west.x, EPS);
        assertEquals(0.0, west.z, EPS);

        Vec3 north = Freecam.computeTargetVelocity(keys(true, false, false, false, false, false, false), 180.0F, 1.5, 0.8, 1.8);
        assertEquals(0.0, north.x, EPS);
        assertEquals(-1.5, north.z, EPS);

        Vec3 east = Freecam.computeTargetVelocity(keys(true, false, false, false, false, false, false), -90.0F, 1.5, 0.8, 1.8);
        assertEquals(1.5, east.x, EPS);
        assertEquals(0.0, east.z, EPS);

        // Diagonal input normalizes horizontal speed rather than multiplying by sqrt(2).
        Vec3 diag = Freecam.computeTargetVelocity(keys(true, false, true, false, false, false, false), 37.0F, 2.0, 0.8, 1.8);
        assertEquals(2.0, Math.hypot(diag.x, diag.z), EPS);

        // Jump/Shift vertical impulse and Sprint boost.
        Vec3 ascendSprint = Freecam.computeTargetVelocity(keys(true, false, false, false, true, false, true), 0.0F, 1.0, 0.5, 2.0);
        assertEquals(0.0, ascendSprint.x, EPS);
        assertEquals(1.0, ascendSprint.y, EPS);
        assertEquals(2.0, ascendSprint.z, EPS);

        Vec3 descend = Freecam.computeTargetVelocity(keys(false, false, false, false, false, true, false), 0.0F, 1.0, 0.8, 2.0);
        assertEquals(-0.8, descend.y, EPS);
    }

    @Test
    void smoothVelocityGlidesAndSnapsCleanlyToRest() {
        Vec3 target = new Vec3(2.0, 0.0, 0.0);
        Vec3 mid = Freecam.stepVelocity(Vec3.ZERO, target, true);
        assertTrue(mid.x > 0.0 && mid.x < 2.0, "smooth start should ease toward target");

        // Decelerating smoothly eventually snaps tiny residuals cleanly to Vec3.ZERO.
        Vec3 vel = mid;
        for (int i = 0; i < 40; i++) {
            vel = Freecam.stepVelocity(vel, Vec3.ZERO, true);
        }
        assertEquals(Vec3.ZERO, vel);

        // With Smooth off, velocity matches target immediately.
        assertEquals(target, Freecam.stepVelocity(Vec3.ZERO, target, false));
        assertEquals(Vec3.ZERO, Freecam.stepVelocity(target, Vec3.ZERO, false));
    }

    @Test
    void turnAndScrollClampWithinValidBounds() {
        assertEquals(45.0F, Freecam.advanceYaw(30.0F, 100.0), 1.0E-4F);
        assertEquals(90.0F, Freecam.advancePitch(85.0F, 200.0), 1.0E-4F);
        assertEquals(-90.0F, Freecam.advancePitch(-85.0F, -200.0), 1.0E-4F);

        assertEquals(1.1F, Freecam.stepScrolledSpeed(1.0F, 1, 0.1F, 0.1F, 10.0F), 1.0E-5F);
        assertEquals(0.1F, Freecam.stepScrolledSpeed(0.1F, -5, 0.1F, 0.1F, 10.0F), 1.0E-5F);
        assertEquals(10.0F, Freecam.stepScrolledSpeed(9.9F, 5, 0.1F, 0.1F, 10.0F), 1.0E-5F);

        ModuleManager manager = new ModuleManager();
        manager.register(new Freecam());
        Freecam registered = manager.get(Freecam.class);
        assertEquals(ModuleCategory.RENDER, registered.getCategory());
        assertSame(registered, manager.find("freecam").orElseThrow());
        assertEquals(Freecam.ViewMode.NORMAL, registered.getViewMode());
        assertEquals(Freecam.FpvTheme.MODERN, registered.getFpvTheme());
        assertFalse(registered.isFpvMode());

        // Safe toggle in headless environment with mc == null.
        assertFalse(registered.isActive());
        assertFalse(Freecam.blocksInteractions());
        assertFalse(Freecam.replacesCrosshair());
        registered.setEnabled(true);
        assertTrue(registered.isEnabled());
        assertFalse(registered.isActive());
        assertFalse(Freecam.replacesCrosshair());
        registered.setEnabled(false);
        assertFalse(registered.isEnabled());
    }
}
