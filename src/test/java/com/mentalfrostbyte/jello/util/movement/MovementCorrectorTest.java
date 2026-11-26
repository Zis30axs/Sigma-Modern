package com.mentalfrostbyte.jello.util.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.event.EventState;
import com.mentalfrostbyte.jello.event.impl.game.EventTick;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventJump;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventStrafe;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class MovementCorrectorTest {

    private static final Input W = new Input(true, false, false, false, false, false, false);
    private static final Input WD = new Input(true, false, false, true, false, false, false);
    private static final Input D = new Input(false, false, false, true, false, false, false);

    private final MovementCorrector corrector = new MovementCorrector();
    private final Object owner = new Object();

    @Test
    void aRequestLastsForTheTickItWasMadeIn() {
        this.corrector.request(this.owner, new Rotation(90.0F, 10.0F), MovementCorrection.STRICT);
        assertNotNull(this.corrector.active());

        this.corrector.onTick(new EventTick(EventState.PRE));
        assertNotNull(this.corrector.active(), "the start of the next tick is not its end");

        this.corrector.onTick(new EventTick(EventState.POST));
        assertNull(this.corrector.active());
    }

    @Test
    void theHigherPriorityWinsAndTheFirstWinsATie() {
        Object other = new Object();
        this.corrector.request(this.owner, new Rotation(10.0F, 0.0F), MovementCorrection.STRICT, 0);
        this.corrector.request(other, new Rotation(20.0F, 0.0F), MovementCorrection.STRICT, 0);
        assertSame(this.owner, this.corrector.active().owner());

        this.corrector.request(other, new Rotation(20.0F, 0.0F), MovementCorrection.STRICT, 5);
        assertSame(other, this.corrector.active().owner());

        this.corrector.request(this.owner, new Rotation(30.0F, 0.0F), MovementCorrection.STRICT, 1);
        assertSame(other, this.corrector.active().owner(), "a lower one does not displace it");
    }

    @Test
    void anOwnerAsksAgainToChangeItsOwnRequest() {
        this.corrector.request(this.owner, new Rotation(10.0F, 0.0F), MovementCorrection.STRICT);
        this.corrector.request(this.owner, new Rotation(40.0F, 5.0F), MovementCorrection.SILENT);
        assertEquals(40.0F, this.corrector.active().look().yaw());
        assertEquals(MovementCorrection.SILENT, this.corrector.active().mode());
    }

    @Test
    void onlyTheOwnerCanWithdrawARequest() {
        this.corrector.request(this.owner, new Rotation(10.0F, 0.0F), MovementCorrection.STRICT);
        this.corrector.release(new Object());
        assertNotNull(this.corrector.active());
        this.corrector.release(this.owner);
        assertNull(this.corrector.active());
    }

    @Test
    void aRequestNeedsAnOwnerAFacingAndAMode() {
        Rotation look = new Rotation(0.0F, 0.0F);
        assertThrows(NullPointerException.class, () -> this.corrector.request(null, look, MovementCorrection.STRICT));
        assertThrows(NullPointerException.class, () -> this.corrector.request(this.owner, null, MovementCorrection.STRICT));
        assertThrows(NullPointerException.class, () -> this.corrector.request(this.owner, look, null));
    }

    @Test
    void withNoRequestNothingIsChanged() {
        EventStrafe strafe = new EventStrafe(33.0F);
        this.corrector.onStrafe(strafe);
        assertEquals(33.0F, strafe.getYaw());
        assertSame(W, this.corrector.correct(W, 90.0F));
        assertNull(this.corrector.lookAngle());
        assertEquals(12.0F, this.corrector.xRot(12.0F));
    }

    @Test
    void offAsksForNothingToBeCorrected() {
        this.corrector.request(this.owner, new Rotation(90.0F, 10.0F), MovementCorrection.OFF);
        EventStrafe strafe = new EventStrafe(33.0F);
        this.corrector.onStrafe(strafe);
        assertEquals(33.0F, strafe.getYaw());
        assertSame(W, this.corrector.correct(W, 0.0F));
        assertNull(this.corrector.lookAngle());
        assertEquals(12.0F, this.corrector.xRot(12.0F));
    }

    @Test
    void strictWalksAndJumpsByTheReportedYawAndLeavesTheKeys() {
        this.corrector.request(this.owner, new Rotation(90.0F, 10.0F), MovementCorrection.STRICT);

        EventStrafe strafe = new EventStrafe(33.0F);
        this.corrector.onStrafe(strafe);
        assertEquals(90.0F, strafe.getYaw());

        EventJump jump = new EventJump(0.42F, 33.0F);
        this.corrector.onJump(jump);
        assertEquals(90.0F, jump.getYaw());
        assertEquals(0.42F, jump.getJumpPower());

        assertSame(W, this.corrector.correct(W, 0.0F), "Strict does not turn the keys");
    }

    @Test
    void theJumpIsOnlyTurnedBeforeTheImpulseLands() {
        this.corrector.request(this.owner, new Rotation(90.0F, 10.0F), MovementCorrection.STRICT);
        EventJump jump = new EventJump(0.42F, 33.0F);
        jump.setState(EventState.POST);
        this.corrector.onJump(jump);
        assertEquals(33.0F, jump.getYaw());
    }

    @Test
    void theElytraAndTheSwimmerAreGivenTheReportedLook() {
        this.corrector.request(this.owner, new Rotation(90.0F, 30.0F), MovementCorrection.STRICT);
        assertEquals(30.0F, this.corrector.xRot(-45.0F));
        Vec3 look = this.corrector.lookAngle();
        assertNotNull(look);
        Vec3 expected = Vec3.directionFromRotation(30.0F, 90.0F);
        assertEquals(expected.x, look.x, 1e-9);
        assertEquals(expected.y, look.y, 1e-9);
        assertEquals(expected.z, look.z, 1e-9);
    }

    @Test
    void theKeysAreTurnedByTheModesThatTurnThem() {
        assertFalse(this.corrector.turnsKeys());
        this.corrector.request(this.owner, new Rotation(0.0F, 0.0F), MovementCorrection.STRICT);
        assertFalse(this.corrector.turnsKeys());

        this.corrector.request(this.owner, new Rotation(0.0F, 0.0F), MovementCorrection.SILENT);
        assertTrue(this.corrector.turnsKeys());
        assertEquals(D, this.corrector.correct(W, 90.0F));

        this.corrector.request(this.owner, new Rotation(0.0F, 0.0F), MovementCorrection.CLAUDE3);
        assertTrue(this.corrector.turnsKeys());
        assertEquals(D, this.corrector.correct(W, 90.0F));
    }

    @Test
    void claude3HoldsItsDirectionFromOneTickToTheNextAndForgetsItWhenItStops() {
        this.corrector.request(this.owner, new Rotation(0.0F, 0.0F), MovementCorrection.CLAUDE3);
        assertEquals(W, this.corrector.correct(W, 5.0F));
        assertEquals(W, this.corrector.correct(W, 24.0F), "nearer the diagonal, but it was going forward");
        assertEquals(WD, this.corrector.correct(W, 40.0F));

        // The request ends: nothing is held over to the next one.
        this.corrector.onTick(new EventTick(EventState.POST));
        assertSame(W, this.corrector.correct(W, 24.0F));
        this.corrector.request(this.owner, new Rotation(0.0F, 0.0F), MovementCorrection.CLAUDE3);
        assertEquals(WD, this.corrector.correct(W, 24.0F));
    }

    @Test
    void legacyDiagonalsKeepUnnormalizedMagnitudeWhenStraightKeysTurnDiagonalAndBack() {
        // <= 1.21.4: W (0, 1) turned 45 degrees to W+D (-1, 1) must stay (-1, 1), keeping y == 1.0 >= 0.8 for sprinting.
        Vec2 straightToDiag = MovementCorrector.scaleTurnedMoveVector(
                W, new Vec2(0.0F, 1.0F), new Vec2(-1.0F, 1.0F), false);
        assertEquals(-1.0F, straightToDiag.x, 1.0E-6F);
        assertEquals(1.0F, straightToDiag.y, 1.0E-6F);

        // <= 1.21.4: W+D (-1, 1) turned back to W (0, 1) must stay (0, 1), never spiking to 1.4142.
        Vec2 diagToStraight = MovementCorrector.scaleTurnedMoveVector(
                WD, new Vec2(-1.0F, 1.0F), new Vec2(0.0F, 1.0F), false);
        assertEquals(0.0F, diagToStraight.x, 1.0E-6F);
        assertEquals(1.0F, diagToStraight.y, 1.0E-6F);

        // Scalar input scaling (e.g. SouthSide 0.2x) is still preserved proportionally across key turning.
        Vec2 slowed = MovementCorrector.scaleTurnedMoveVector(
                WD, new Vec2(-0.2F, 0.2F), new Vec2(0.0F, 1.0F), false);
        assertEquals(0.0F, slowed.x, 1.0E-5F);
        assertEquals(0.2F, slowed.y, 1.0E-5F);

        // > 1.21.4: unit-normalized vectors stay unit-normalized.
        Vec2 modernDiag = new Vec2(-1.0F, 1.0F).normalized();
        Vec2 modernTurned = MovementCorrector.scaleTurnedMoveVector(W, new Vec2(0.0F, 1.0F), modernDiag, true);
        assertEquals(1.0F, modernTurned.length(), 1.0E-5F);
    }

    @Test
    void synthesizedSprintFollowsTurnedForwardWhilePhysicalSprintKeyIsPreserved() {
        Input sWithSprint = new Input(false, true, false, false, false, false, true);
        Input wWithSprint = new Input(true, false, false, false, false, false, true);

        // Camera S turned 180 degrees to reported W keeps Sprint's request.
        this.corrector.request(this.owner, new Rotation(180.0F, 0.0F), MovementCorrection.SILENT);
        Input turnedForward = MovementCorrector.reconcileTurnedSprint(this.corrector.correct(sWithSprint, 0.0F), false);
        assertTrue(turnedForward.forward());
        assertTrue(turnedForward.sprint());

        // Camera W turned 180 degrees to reported S drops synthesized sprint, unless physical sprint key is held.
        Input turnedBack = this.corrector.correct(wWithSprint, 0.0F);
        assertFalse(MovementCorrector.reconcileTurnedSprint(turnedBack, false).sprint());
        assertTrue(MovementCorrector.reconcileTurnedSprint(turnedBack, true).sprint());
    }
}
