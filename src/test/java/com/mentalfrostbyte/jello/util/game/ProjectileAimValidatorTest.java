package com.mentalfrostbyte.jello.util.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProjectileAimValidatorTest {
    @Test
    void aCrossingEntityIsDetectedAtTheTimeTheProjectileReachesIt() {
        AABB obstacle = new AABB(2.2, 0, 1.7, 2.8, 2, 2.3);
        Vec3 from = new Vec3(0, 1, 0), to = new Vec3(0, 1, 4);
        assertTrue(ProjectileAimValidator.intersectsMovingBox(obstacle, new Vec3(-1, 0, 0),
                from, to, 2, 3, 0));
        assertFalse(ProjectileAimValidator.intersectsMovingBox(obstacle, Vec3.ZERO,
                from, to, 2, 3, 0), "the moving obstacle starts outside the shot's static ray");
    }

    @Test
    void anEntityCrossingAfterTheProjectilePassedDoesNotCauseATimeIndependentHit() {
        AABB obstacle = new AABB(2.2, 0, 1.7, 2.8, 2, 2.3);
        assertFalse(ProjectileAimValidator.intersectsMovingBox(obstacle, new Vec3(-1, 0, 0),
                new Vec3(0, 1, 0), new Vec3(0, 1, 4), 0, 1, 0));
    }

    @Test
    void fractionalFinalTicksKeepTheirActualArrivalTime() {
        AABB obstacle = new AABB(1.2, 0, 0.9, 1.3, 2, 1.1);
        assertTrue(ProjectileAimValidator.intersectsMovingBox(obstacle, new Vec3(-1, 0, 0),
                new Vec3(0, 1, 0), new Vec3(0, 1, 2), 1, 1.5, 0));
        assertFalse(ProjectileAimValidator.intersectsMovingBox(obstacle, new Vec3(-1, 0, 0),
                new Vec3(0, 1, 0), new Vec3(0, 1, 2), 1, 2, 0));
    }

    @Test
    void projectileMarginRejectsAGrazeAndStartingInsideRejectsImmediately() {
        AABB obstacle = new AABB(-0.3, 0, 1, 0.3, 2, 2);
        Vec3 from = new Vec3(0.4, 1, 0), to = new Vec3(0.4, 1, 3);
        assertFalse(ProjectileAimValidator.intersectsMovingBox(obstacle, Vec3.ZERO, from, to, 0, 1, 0));
        assertTrue(ProjectileAimValidator.intersectsMovingBox(obstacle, Vec3.ZERO, from, to, 0, 1, 0.15));
        assertTrue(ProjectileAimValidator.intersectsMovingBox(obstacle, Vec3.ZERO,
                new Vec3(0, 1, 1.5), new Vec3(0, 1, 4), 0, 1, 0));
    }

    @Test
    void invalidGeometryCannotTurnIntoAClearPath() {
        AABB obstacle = new AABB(10, 0, 10, 11, 2, 11);
        assertTrue(ProjectileAimValidator.intersectsMovingBox(obstacle, new Vec3(Double.NaN, 0, 0),
                Vec3.ZERO, new Vec3(0, 0, 4), 0, 1, 0));
        assertTrue(ProjectileAimValidator.intersectsMovingBox(obstacle, Vec3.ZERO,
                Vec3.ZERO, new Vec3(0, 0, 4), 2, 1, 0));
        assertTrue(ProjectileAimValidator.intersectsMovingBox(obstacle, Vec3.ZERO,
                Vec3.ZERO, new Vec3(0, 0, 4), 0, 1, Double.POSITIVE_INFINITY));
    }
}
