package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockFlySouthSidePredictionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void fallingPredictionCarriesMomentumThenAppliesDragAndGravity() {
        var predicted = BlockFlySouthSideMode.predict(new Vec3(-3, 64, 2), new Vec3(0.5, -0.3, 0),
                0, 0, 0, false, 1.62F);
        assertEquals(-2.5, predicted.nextEyes().x, 1.0E-9);
        assertEquals(65.32, predicted.nextEyes().y, 1.0E-6);
        assertEquals(-2.045, predicted.afterTwo().x, 1.0E-9);
        assertEquals(63.3276, predicted.afterTwo().y, 1.0E-9);
    }

    @Test
    void sprintAirAccelerationUsesTheReportedFacing() {
        var south = BlockFlySouthSideMode.predict(Vec3.ZERO, Vec3.ZERO, 0, 1, 0, true, 1.62F);
        var west = BlockFlySouthSideMode.predict(Vec3.ZERO, Vec3.ZERO, 0, 1, 90, true, 1.62F);
        assertEquals(0.026, south.nextEyes().z, 1.0E-9);
        assertEquals(-0.026, west.nextEyes().x, 1.0E-9);
        assertEquals(0.07566, south.afterTwo().z, 1.0E-9);
        assertEquals(-0.07566, west.afterTwo().x, 1.0E-9);
    }

    @Test
    void diagonalInputsDoNotCreateExtraAcceleration() {
        var straight = BlockFlySouthSideMode.predict(Vec3.ZERO, Vec3.ZERO, 0, 1, 0, false, 0);
        var diagonal = BlockFlySouthSideMode.predict(Vec3.ZERO, Vec3.ZERO, 1, 1, 0, false, 0);
        assertEquals(straight.nextEyes().horizontalDistance(), diagonal.nextEyes().horizontalDistance(), 1.0E-9);
    }
}
