package com.mentalfrostbyte.jello.util.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReportedRotationsTest {

    private static final float EPSILON = 1.0E-4F;

    @Test
    void beforeTheFirstMovementReportUsesTheCurrentPlayerLook() {
        ReportedRotations rotations = new ReportedRotations();

        assertEquals(new ReportedRotations.Sample(30.0F, -20.0F, 10.0F),
                rotations.sample(0.5F, 30.0F, -20.0F, 10.0F));
        assertEquals(new ReportedRotations.Sample(60.0F, 15.0F, 20.0F),
                rotations.sample(0.5F, 60.0F, 15.0F, 20.0F));
    }

    @Test
    void aReportIsVisibleImmediatelyWithoutWaitingForATick() {
        ReportedRotations rotations = new ReportedRotations();
        rotations.record(120.0F, 25.0F);

        ReportedRotations.Sample first = rotations.sample(0.0F, 0.0F, 0.0F, 10.0F);
        assertEquals(120.0F, first.yaw());
        assertEquals(25.0F, first.pitch());
        rotations.record(-45.0F, -10.0F);
        ReportedRotations.Sample next = rotations.sample(0.0F, 0.0F, 0.0F, 10.0F);
        assertEquals(-45.0F, next.yaw());
        assertEquals(-10.0F, next.pitch());
    }

    @Test
    void ticksAndCameraChangesDoNotReplaceTheLastReportedHead() {
        ReportedRotations rotations = new ReportedRotations();
        rotations.record(450.0F, 135.0F);
        rotations.tick(20.0F, 0.0F, 0.0, 0.0, false, 50.0F, false);
        rotations.tick(40.0F, 10.0F, 0.0, 0.0, false, 50.0F, false);

        ReportedRotations.Sample sample = rotations.sample(0.5F, 80.0F, 30.0F, 15.0F);
        assertEquals(450.0F, sample.yaw());
        // A direction indicator needs the actual vector even when a module sends pitch beyond +/-90.
        assertEquals(135.0F, sample.pitch());
    }

    @Test
    void anInvalidReportCannotReplaceEitherHalfOfThePreviousLook() {
        ReportedRotations rotations = new ReportedRotations();
        rotations.record(60.0F, -15.0F);
        rotations.record(Float.NaN, 80.0F);
        rotations.record(20.0F, Float.POSITIVE_INFINITY);
        rotations.record(Float.NEGATIVE_INFINITY, Float.NaN);

        ReportedRotations.Sample sample = rotations.sample(1.0F, 0.0F, 0.0F, 0.0F);
        assertEquals(60.0F, sample.yaw());
        assertEquals(-15.0F, sample.pitch());
    }

    @Test
    void inferredBodyBlendsAcrossTheWrapUsingTheShortArc() {
        ReportedRotations rotations = new ReportedRotations();
        rotations.tick(170.0F, 170.0F, 0.0, 0.0, false, 0.0F, false);
        rotations.record(-170.0F, 0.0F);
        rotations.tick(170.0F, 170.0F, 0.0, 0.0, false, 0.0F, false);

        assertEquals(170.0F, rotations.sample(0.0F, 0, 0, 0).bodyYaw(), EPSILON);
        assertEquals(180.0F, rotations.sample(0.5F, 0, 0, 0).bodyYaw(), EPSILON);
        assertEquals(190.0F, rotations.sample(1.0F, 0, 0, 0).bodyYaw(), EPSILON);
        assertEquals(-170.0F, rotations.sample(0.5F, 0, 0, 0).yaw(), EPSILON);
    }

    @Test
    void aPoseThatControlsFacingResetsTheBodyWithoutErasingTheReportedHead() {
        ReportedRotations rotations = new ReportedRotations();
        rotations.record(150.0F, 30.0F);
        rotations.tick(0, 0, 0, 0, false, 50, false);
        rotations.tick(0, 0, 0, 0, false, 50, false);
        rotations.tick(40, 40, 0, 0, false, 50, true);

        assertEquals(new ReportedRotations.Sample(150.0F, 30.0F, 40.0F),
                rotations.sample(0.0F, 0, 0, 0));
        assertEquals(40.0F, rotations.sample(1.0F, 0, 0, 0).bodyYaw());
    }

    @Test
    void aNewPlayerStateDoesNotInheritThePreviousPlayersReport() {
        ReportedRotations previousPlayer = new ReportedRotations();
        previousPlayer.record(180.0F, 85.0F);
        previousPlayer.tick(0, 0, 0, 0, false, 50, false);

        ReportedRotations nextPlayer = new ReportedRotations();
        assertEquals(new ReportedRotations.Sample(15.0F, -10.0F, 5.0F),
                nextPlayer.sample(1.0F, 15.0F, -10.0F, 5.0F));
    }

    @Test
    void invalidInterpolationAndFallbackValuesNeverProduceInvalidGeometry() {
        ReportedRotations rotations = new ReportedRotations();
        ReportedRotations.Sample empty = rotations.sample(Float.NaN, Float.NaN, Float.POSITIVE_INFINITY, Float.NaN);
        assertTrue(Float.isFinite(empty.yaw()));
        assertTrue(Float.isFinite(empty.pitch()));
        assertTrue(Float.isFinite(empty.bodyYaw()));

        rotations.tick(0, 0, 0, 0, false, 50, false);
        rotations.record(90, 0);
        rotations.tick(0, 0, 0, 0, false, 50, false);
        assertEquals(0.0F, rotations.sample(-1.0F, 0, 0, 0).bodyYaw());
        assertEquals(40.0F, rotations.sample(2.0F, 0, 0, 0).bodyYaw());
        assertEquals(40.0F, rotations.sample(Float.NaN, 0, 0, 0).bodyYaw());
    }

    @Test
    void overflowWhileEstimatingBodyDoesNotPoisonLaterSamples() {
        ReportedRotations rotations = new ReportedRotations();
        rotations.tick(Float.MAX_VALUE, Float.MAX_VALUE, 0, 0, false, 50, false);
        rotations.record(-Float.MAX_VALUE, 0);
        rotations.tick(0, 0, 0, 0, true, 50, false);

        assertTrue(Float.isFinite(rotations.sample(0.5F, 0, 0, 0).bodyYaw()));
    }
}
