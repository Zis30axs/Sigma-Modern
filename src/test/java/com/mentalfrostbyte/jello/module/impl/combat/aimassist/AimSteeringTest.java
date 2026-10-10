package com.mentalfrostbyte.jello.module.impl.combat.aimassist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.impl.combat.aimassist.AimSteering.Turn;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AimSteeringTest {
    @Test
    void lockConvergesOverElapsedTimeAtDifferentFrameRates() {
        Turn thirtyFps = trackLock(30, 0.5);
        Turn oneFortyFourFps = trackLock(144, 0.5);

        assertEquals(thirtyFps.yaw(), oneFortyFourFps.yaw(), 1.0E-9);
        assertEquals(thirtyFps.pitch(), oneFortyFourFps.pitch(), 1.0E-9);
        assertTrue(Math.hypot(thirtyFps.yaw(), thirtyFps.pitch()) < 0.001,
                "The same half-second slide must settle near the target at either frame rate");
    }

    @Test
    void assistConvergesOverElapsedTimeAtDifferentFrameRates() {
        Turn thirtyFps = trackAssist(30);
        Turn oneFortyFourFps = trackAssist(144);

        assertEquals(thirtyFps.yaw(), oneFortyFourFps.yaw(), 1.0E-9);
        assertEquals(thirtyFps.pitch(), oneFortyFourFps.pitch(), 1.0E-9);
        assertTrue(Math.hypot(thirtyFps.yaw(), thirtyFps.pitch()) < 0.5);
    }

    @Test
    void lockKeepsDiagonalSpeedWithinTheCapAndNeverCrossesTheTarget() {
        double yaw = 80.0, pitch = -60.0;
        for (int frame = 0; frame < 120; frame++) {
            Turn turn = AimSteering.lock(yaw, pitch, 90.0, 1.0 / 60.0);
            assertTrue(Math.hypot(turn.yaw(), turn.pitch()) <= 1.5 + 1.0E-12);
            assertTrue(turn.yaw() >= 0.0 && turn.yaw() <= yaw);
            assertTrue(turn.pitch() <= 0.0 && turn.pitch() >= pitch);
            yaw -= turn.yaw();
            pitch -= turn.pitch();
        }
        assertTrue(Math.hypot(yaw, pitch) < 0.001);
    }

    @Test
    void nearbyTargetsReduceBothMouseSensitivityAndAutomaticTurnSpeed() {
        double previousScale = 1.0;
        double previousStep = Double.POSITIVE_INFINITY;
        for (double error : new double[] {20, 10, 5, 2, 0.5, 0}) {
            double scale = AimSteering.mouseScale(error, 0.7);
            Turn turn = AimSteering.assist(error, 0, 0, 0, 240, 0.7, 1.0 / 60.0);
            assertTrue(scale <= previousScale, "Mouse friction must strengthen as the crosshair approaches");
            assertTrue(scale > 0.0, "Proximity must never freeze physical mouse input");
            assertTrue(turn.yaw() <= previousStep, "Attraction must ease down near the target");
            assertTrue(turn.yaw() >= 0.0 && turn.yaw() <= error);
            previousScale = scale;
            previousStep = turn.yaw();
        }
    }

    @Test
    void zeroStrengthPreservesTheUsersExactMovement() {
        for (double error : new double[] {0, 0.1, 10, 170}) {
            Turn turn = AimSteering.assist(error, -error, -3.25, 1.75, 240, 0, 1.0 / 60.0);
            assertEquals(-3.25, turn.yaw(), 0.0);
            assertEquals(1.75, turn.pitch(), 0.0);
        }
    }

    @Test
    void assistStillAllowsTheUserToPullAwayFromTheTarget() {
        Turn turn = AimSteering.assist(1, -1, -3, 3, 240, 1.0, 1.0 / 60.0);

        assertTrue(turn.yaw() < 0.0);
        assertTrue(turn.pitch() > 0.0);
    }

    @Test
    void noisyLearnedVelocitiesCannotOvershootReverseOrExceedTheSpeedCap() {
        Random random = new Random(2389);
        for (int sample = 0; sample < 1000; sample++) {
            double yawError = random.nextDouble(-2, 2);
            double pitchError = random.nextDouble(-2, 2);
            Turn turn = AimSteering.learned(yawError, pitchError,
                    random.nextGaussian() * 1000, random.nextGaussian() * 1000, 120, 1.0 / 60.0);

            assertTrue(turn.yaw() * yawError >= 0.0);
            assertTrue(turn.pitch() * pitchError >= 0.0);
            assertTrue(Math.abs(turn.yaw()) <= Math.abs(yawError));
            assertTrue(Math.abs(turn.pitch()) <= Math.abs(pitchError));
            assertTrue(Math.hypot(turn.yaw(), turn.pitch()) <= 2.0 + 1.0E-12);
        }
        assertEquals(new Turn(0, 0), AimSteering.learned(0, 0, 500, -500, 120, 1.0 / 60.0),
                "Residual noise must not shake a crosshair that is already centred");
    }

    @Test
    void invalidFrameIntervalsCannotCauseALargeCatchUpTurn() {
        for (double seconds : new double[] {0, -1, 0.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(0.0, AimSteering.frameSeconds(seconds));
            assertEquals(new Turn(0, 0), AimSteering.lock(100, 20, 240, seconds));
            assertEquals(new Turn(0, 0), AimSteering.learned(100, 20, 500, 100, 240, seconds));
        }
        Turn slowFrame = AimSteering.lock(100, 20, 240, 0.1);
        assertTrue(Math.hypot(slowFrame.yaw(), slowFrame.pitch()) <= 12.0 + 1.0E-12,
                "A long but valid frame is capped to 50 ms of assistance");
    }

    @Test
    void crossingTheYawSeamKeepsTheShortestContinuousTurn() {
        double cameraYaw = 179.0;
        double error = AimSteering.wrap(-179.0 - cameraYaw);
        Turn turn = AimSteering.lock(error, 0, 240, 1.0 / 60.0);

        assertEquals(2.0, error, 0.0);
        assertTrue(turn.yaw() > 0.0 && turn.yaw() < 2.0);
        assertTrue(cameraYaw + turn.yaw() > cameraYaw,
                "Crossing north should continue forward, not spin back through a full turn");
        assertEquals(-2.0, AimSteering.wrap(179.0 - -179.0), 0.0);
        assertEquals(2.0, AimSteering.wrap(-179.0 - (179.0 + 3600)), 0.0);
    }

    private static Turn trackLock(int fps, double duration) {
        double yaw = 20, pitch = -10;
        for (int frame = 0; frame < Math.round(fps * duration); frame++) {
            Turn turn = AimSteering.lock(yaw, pitch, 900, 1.0 / fps);
            yaw -= turn.yaw();
            pitch -= turn.pitch();
        }
        return new Turn(yaw, pitch);
    }

    private static Turn trackAssist(int fps) {
        double yaw = 20, pitch = -10;
        for (int frame = 0; frame < fps; frame++) {
            Turn turn = AimSteering.assist(yaw, pitch, 0, 0, 900, 0.7, 1.0 / fps);
            yaw -= turn.yaw();
            pitch -= turn.pitch();
        }
        return new Turn(yaw, pitch);
    }
}
