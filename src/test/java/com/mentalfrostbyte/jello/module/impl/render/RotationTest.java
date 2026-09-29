package com.mentalfrostbyte.jello.module.impl.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import org.junit.jupiter.api.Test;

class RotationTest {

    private static final float EPSILON = 1.0E-4F;

    @Test
    void isARenderModuleNamedRotation() {
        Rotation rotation = new Rotation();
        assertEquals("Rotation", rotation.getName());
        assertEquals(ModuleCategory.RENDER, rotation.getCategory());
    }

    @Test
    void aStandingBodyStaysWhileTheHeadIsWithinReach() {
        assertEquals(0.0F, Rotation.stepBody(0.0F, 30.0F, 0.0, 0.0, false, 50.0F), EPSILON);
    }

    @Test
    void aStandingBodyIsPulledAlongByAHeadTurnedTooFar() {
        assertEquals(40.0F, Rotation.stepBody(0.0F, 90.0F, 0.0, 0.0, false, 50.0F), EPSILON);
        assertEquals(-40.0F, Rotation.stepBody(0.0F, -90.0F, 0.0, 0.0, false, 50.0F), EPSILON);
    }

    @Test
    void theReachIsWhatTheCallerSaysSoABlockingPlayerKeepsTheHeadCloser() {
        assertEquals(25.0F, Rotation.stepBody(0.0F, 40.0F, 0.0, 0.0, false, 15.0F), EPSILON);
    }

    @Test
    void theHeadIsComparedAroundTheCircle() {
        // 350 is ten degrees from 0, and 810 is ninety: only the second is out of reach.
        assertEquals(0.0F, Rotation.stepBody(0.0F, 350.0F, 0.0, 0.0, false, 50.0F), EPSILON);
        assertEquals(40.0F, Rotation.stepBody(0.0F, 810.0F, 0.0, 0.0, false, 50.0F), EPSILON);
    }

    @Test
    void aSwingingBodyEasesToTheHead() {
        assertEquals(12.0F, Rotation.stepBody(0.0F, 40.0F, 0.0, 0.0, true, 50.0F), EPSILON);
    }

    @Test
    void aWalkingBodyEasesToTheWayItWalks() {
        // South is +z, yaw 0: the body at 10 moves three tenths of the way to it.
        assertEquals(7.0F, Rotation.stepBody(10.0F, 0.0F, 0.0, 0.1, false, 50.0F), EPSILON);
    }

    @Test
    void aBodyWalkingBackwardsKeepsFacingTheHeadsWay() {
        assertEquals(0.0F, Rotation.stepBody(0.0F, 0.0F, 0.0, -0.1, false, 50.0F), EPSILON);
        // Well short of that speed is standing still.
        assertEquals(10.0F, Rotation.stepBody(10.0F, 0.0F, 0.0, 0.01, false, 50.0F), EPSILON);
    }

    @Test
    void thePitchIsBlendedAndKeptToWhatAHeadCanDo() {
        assertEquals(15.0F, Rotation.shownPitch(0.0F, 30.0F, 0.5F), EPSILON);
        assertEquals(90.0F, Rotation.shownPitch(80.0F, 100.0F, 1.0F), EPSILON);
        assertEquals(-90.0F, Rotation.shownPitch(-80.0F, -120.0F, 1.0F), EPSILON);
    }
}
