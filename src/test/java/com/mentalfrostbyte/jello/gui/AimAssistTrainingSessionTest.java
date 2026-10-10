package com.mentalfrostbyte.jello.gui;

import static org.junit.jupiter.api.Assertions.*;

import com.mentalfrostbyte.jello.module.impl.combat.aimassist.AimStyleModel;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AimAssistTrainingSessionTest {
    @Test void movingWithoutAnAttackDoesNotTrainAndClickCommitsTheLeadIn() {
        AimStyleModel model = new AimStyleModel();
        AimAssistTrainingSession session = session(model);
        session.start();
        session.update(0.025, 0, 0, true);
        session.update(0.025, 1, -0.5, true);
        assertEquals(0, model.sampleCount(), "Hovering alone is not an attack demonstration");
        session.pressAttack(1, -0.5);
        assertEquals(1, model.sampleCount());
        assertEquals(1, model.scenarioSamples(0));
        assertEquals(1, session.hits() + session.misses());
    }

    @Test void recordedMovementUsesPreMovementErrorsAndDegreesPerSecond() {
        AimStyleModel model = new AimStyleModel();
        AimAssistTrainingSession session = session(model);
        double targetYaw = session.targetYaw(), targetPitch = session.targetPitch();
        session.start();
        session.update(0.025, 0, 0, true);
        session.update(0.025, 1, -0.5, true);
        session.pressAttack(1, -0.5);

        AimStyleModel expected = new AimStyleModel();
        AimStyleModel.Input input = new AimStyleModel.Input(targetYaw, targetPitch, 0, 0, 0, 0);
        expected.observe(input, new AimStyleModel.Velocity(40, -20), 0);
        assertEquals(expected.predict(input), model.predict(input));
        assertEquals(expected.residualDeviation(), model.residualDeviation());
    }

    @Test void onlyRecentMovementBelongsToAClick() {
        AimStyleModel model = new AimStyleModel();
        AimAssistTrainingSession session = session(model);
        session.start();
        session.update(0.025, 29, 17, true);
        session.update(0.025, 30, 17, true);
        for (int i = 0; i < 30; i++) session.update(0.025, 30, 17, true);
        session.pressAttack(30, 17);
        assertEquals(0, model.sampleCount(), "A click after an idle gap must not label stale movement");
    }

    @Test void pauseAndCursorExitDiscardTheLeadInAndHeldAttack() {
        for (boolean pause : new boolean[]{false, true}) {
            AimStyleModel model = new AimStyleModel();
            AimAssistTrainingSession session = session(model);
            session.start();
            session.update(0.025, 0, 0, true);
            session.update(0.025, 1, 0, true);
            if (pause) { session.pause(); session.start(); }
            else session.update(0.025, 2, 0, false);
            session.update(0.025, 20, 0, true);
            session.pressAttack(20, 0);
            assertEquals(0, model.sampleCount(), "Re-entry must seed a fresh movement history");
        }
    }

    @Test void heldAttackCollectsMovementButDiscontinuousFramesCancelIt() {
        AimStyleModel model = new AimStyleModel();
        AimAssistTrainingSession session = session(model);
        session.start();
        session.update(0.025, 0, 0, true);
        session.pressAttack(0, 0);
        session.update(0.025, 1, 0, true);
        assertEquals(1, model.sampleCount());
        session.update(2.0, 20, 0, true);
        session.update(0.025, 21, 0, true);
        session.update(0.025, 22, 0, true);
        assertEquals(1, model.sampleCount(), "A dropped frame clears attack intent and discontinuous input");
    }

    @Test void switchingScenarioDiscardsOldSamplesAndResetClearsTraining() {
        AimStyleModel model = new AimStyleModel();
        AimAssistTrainingSession session = session(model);
        session.start();
        session.update(0.025, 0, 0, true);
        session.update(0.025, 1, 0, true);
        session.selectScenario(1);
        session.update(0.025, 0, 0, true);
        session.update(0.025, 1, 0, true);
        session.pressAttack(1, 0);
        assertEquals(0, model.scenarioSamples(0));
        assertEquals(1, model.scenarioSamples(1));
        assertFalse(model.isReady());
        session.reset();
        assertEquals(0, model.sampleCount());
        assertEquals(0, session.hits() + session.misses());
        assertFalse(session.active());
    }

    @Test void cursorWarpsAreDiscardedInsteadOfTrainingClippedMovements() {
        AimStyleModel model = new AimStyleModel();
        AimAssistTrainingSession session = session(model);
        session.start();
        session.update(0.025, -30, -10, true);
        session.pressAttack(-30, -10);
        session.update(0.025, 30, 10, true);
        assertEquals(0, model.sampleCount());
        session.update(0.025, 29, 10, true);
        session.update(0.025, 28, 10, true);
        assertEquals(0, model.sampleCount(), "A cursor warp also clears the held attack");
    }

    private static AimAssistTrainingSession session(AimStyleModel model) {
        return new AimAssistTrainingSession(model, new Random(12345));
    }
}
