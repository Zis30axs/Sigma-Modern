package com.mentalfrostbyte.jello.module.impl.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SmoothBobbingTest {
    @Test
    void strideLiftIsSmoothAndBoundedAcrossAStepCycle() {
        assertEquals(0.0F, SmoothBobbing.strideLift(0.0F), 1.0E-5F);
        assertEquals(1.0F, SmoothBobbing.strideLift(0.5F), 1.0E-5F);
        assertEquals(0.0F, SmoothBobbing.strideLift(1.0F), 1.0E-5F);
        assertEquals(SmoothBobbing.strideLift(0.25F), SmoothBobbing.strideLift(0.75F), 1.0E-5F);
        assertEquals(0.5F, SmoothBobbing.strideLift(0.25F), 1.0E-5F);
    }

    @Test
    void strideSwayAlternatesSignBetweenLeftAndRightSteps() {
        assertEquals(0.0F, SmoothBobbing.strideSway(0.0F), 1.0E-5F);
        assertEquals(1.0F, SmoothBobbing.strideSway(0.5F), 1.0E-5F);
        assertEquals(0.0F, SmoothBobbing.strideSway(1.0F), 1.0E-5F);
        assertEquals(-1.0F, SmoothBobbing.strideSway(1.5F), 1.0E-5F);
    }

    @Test
    void defaultsMatchExpectedModuleMetadata() {
        SmoothBobbing module = new SmoothBobbing();
        assertEquals("SmoothBobbing", module.getName());
        assertTrue(module.affectsHand());
        assertEquals(5, module.settings().size());
    }
}
