package com.mentalfrostbyte.jello.util.game;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProjectileLaunchTest {
    private static final double EPSILON = 2E-4;

    @Test
    void idleBowPreviewsFullDrawButAnActualDrawUsesNonlinearCharge() {
        assertEquals(1F, ProjectileLaunch.bowPower(false, 0));
        assertEquals(0F, ProjectileLaunch.bowPower(true, 0));
        assertEquals(5F / 12F, ProjectileLaunch.bowPower(true, 10), 1E-6);
        assertEquals(1F, ProjectileLaunch.bowPower(true, 20));
        assertEquals(1F, ProjectileLaunch.bowPower(true, 200));
        assertEquals(0F, ProjectileLaunch.bowPower(true, -1));
    }

    @Test
    void bowTooEarlyToReleaseHasNoShotAndPreviewDoesNotChangePhysics() {
        assertTrue(ProjectileLaunch.bow(Vec3.ZERO, Vec3.ZERO, 0, 0, true, 2).isEmpty());
        var shot = ProjectileLaunch.bow(Vec3.ZERO, Vec3.ZERO, 0, 0, true, 3).getFirst();
        assertFalse(shot.preview());
        var preview = ProjectileLaunch.bow(Vec3.ZERO, Vec3.ZERO, 0, 0, false, 0).getFirst();
        var charged = ProjectileLaunch.bow(Vec3.ZERO, Vec3.ZERO, 0, 0, true, 20).getFirst();
        assertTrue(preview.preview());
        assertEquals(charged.velocity(), preview.velocity());
        assertEquals(new Vec3(0, -0.1F, 0), preview.origin());
        assertEquals(ProjectileLaunch.Physics.ARROW, preview.physics());
        assertEquals(0.05, preview.gravity());
        assertEquals(0.99F, preview.airDrag());
        assertEquals(0.6F, preview.waterDrag());
    }

    @Test
    void actualTridentNeedsTenTicksAndRiptideNeverGetsAProjectile() {
        assertTrue(ProjectileLaunch.canThrowTrident(false, 0, false, false));
        assertFalse(ProjectileLaunch.canThrowTrident(true, 9, false, false));
        assertTrue(ProjectileLaunch.canThrowTrident(true, 10, false, false));
        assertFalse(ProjectileLaunch.canThrowTrident(true, 100, true, false));
        assertFalse(ProjectileLaunch.canThrowTrident(false, 0, true, false));
        assertFalse(ProjectileLaunch.canThrowTrident(true, 100, false, true));
    }

    @Test
    void onGroundSuppressesOnlyTheInheritedVerticalVelocity() {
        Vec3 moving = new Vec3(0.2, -0.3, 0.4);
        assertEquals(new Vec3(0.2, 0, 0.4), ProjectileLaunch.inheritedMovement(moving, true));
        assertEquals(moving, ProjectileLaunch.inheritedMovement(moving, false));
        Vec3 velocity = ProjectileLaunch.initialVelocity(0, 0, 0, 1.5F, moving);
        assertVec(new Vec3(0.2, -0.3, 1.9), velocity);
    }

    @Test
    void launchYawAndPitchCanFollowEitherCameraOrReportedRotation() {
        assertVec(new Vec3(-1.5, 0, 0), ProjectileLaunch.initialVelocity(90, 0, 0, 1.5F, Vec3.ZERO));
        assertVec(new Vec3(0, 1.5, 0), ProjectileLaunch.initialVelocity(0, -90, 0, 1.5F, Vec3.ZERO));
        assertVec(new Vec3(0, 0, -1.5), ProjectileLaunch.initialVelocity(180, 0, 0, 1.5F, Vec3.ZERO));
    }

    @Test
    void potionPitchOffsetAffectsOnlyVerticalComponentBeforeNormalization() {
        Vec3 velocity = ProjectileLaunch.initialVelocity(0, 40, -20, 0.5F, Vec3.ZERO);
        Vec3 expected = new Vec3(0, -Math.sin(Math.toRadians(20)), Math.cos(Math.toRadians(40)))
                .normalize().scale(0.5);
        assertVec(expected, velocity);
        assertEquals(0.5, velocity.length(), 1E-7);
        assertTrue(Math.abs(velocity.y - -Math.sin(Math.toRadians(20)) * 0.5) > 0.02);
    }

    @Test
    void multishotAnglesMatchTheAlternatingVanillaOrder() {
        assertEquals(0F, ProjectileLaunch.spreadAngle(0, 3, 10));
        assertEquals(-10F, ProjectileLaunch.spreadAngle(1, 3, 10));
        assertEquals(10F, ProjectileLaunch.spreadAngle(2, 3, 10));
        assertEquals(10F, ProjectileLaunch.spreadAngle(0, 2, 10));
        assertEquals(-10F, ProjectileLaunch.spreadAngle(1, 2, 10));
        assertEquals(0F, ProjectileLaunch.spreadAngle(0, 1, 10));
    }

    @Test
    void crossbowSideShotsSpreadAroundLocalUpEvenWhenLookingStraightUp() {
        Vec3 center = ProjectileLaunch.crossbowVelocity(30, -90, 0, 3.15F);
        Vec3 left = ProjectileLaunch.crossbowVelocity(30, -90, -10, 3.15F);
        Vec3 right = ProjectileLaunch.crossbowVelocity(30, -90, 10, 3.15F);
        assertEquals(3.15F, left.length(), 1E-7);
        assertEquals(3.15F, right.length(), 1E-7);
        assertEquals(Math.cos(Math.toRadians(10)), left.normalize().dot(center.normalize()), EPSILON);
        assertEquals(Math.cos(Math.toRadians(20)), left.normalize().dot(right.normalize()), EPSILON);
        assertTrue(left.horizontalDistance() > 0.5);
    }

    @Test
    void crossbowHasItsOwnSpeedWithoutInheritingShooterVelocity() {
        assertVec(new Vec3(0, 0, 3.15F), ProjectileLaunch.crossbowVelocity(0, 0, 0, 3.15F));
        assertVec(new Vec3(0, 0, 1.6F), ProjectileLaunch.crossbowVelocity(0, 0, 0, 1.6F));
    }

    @Test
    void fireworkPreviewStopsAtTheGuaranteedLifetimeAndIsBounded() {
        assertEquals(10, ProjectileLaunch.fireworkLifetime(0));
        assertEquals(20, ProjectileLaunch.fireworkLifetime(1));
        assertEquals(40, ProjectileLaunch.fireworkLifetime(3));
        assertEquals(160, ProjectileLaunch.fireworkLifetime(Integer.MAX_VALUE));
        assertEquals(10, ProjectileLaunch.fireworkLifetime(Integer.MIN_VALUE));
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }
}
