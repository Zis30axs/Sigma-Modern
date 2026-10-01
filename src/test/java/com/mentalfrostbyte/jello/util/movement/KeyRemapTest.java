package com.mentalfrostbyte.jello.util.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import org.junit.jupiter.api.Test;

/**
 * The delta is {@code camera yaw - reported yaw}. Yaw grows clockwise seen from above (south 0, west 90), so a camera
 * turned +90 from the reported facing points to the reported facing's right: what the player meant as forward has to be
 * pressed as right.
 */
class KeyRemapTest {

    private static final Input NONE = Input.EMPTY;
    private static final Input W = new Input(true, false, false, false, false, false, false);
    private static final Input S = new Input(false, true, false, false, false, false, false);
    private static final Input A = new Input(false, false, true, false, false, false, false);
    private static final Input D = new Input(false, false, false, true, false, false, false);
    private static final Input WA = new Input(true, false, true, false, false, false, false);
    private static final Input WD = new Input(true, false, false, true, false, false, false);
    private static final Input SA = new Input(false, true, true, false, false, false, false);
    private static final Input SD = new Input(false, true, false, true, false, false, false);

    private static final Input[] EIGHT = {W, WA, A, SA, S, SD, D, WD};

    @Test
    void facingTheSameWayChangesNothing() {
        for (Input keys : EIGHT) {
            assertEquals(keys, KeyRemap.silent(keys, 0.0F));
            assertEquals(keys, KeyRemap.nearest(keys, 0.0F, -1, 8.0F).keys());
            // A whole turn round is no turn either.
            assertEquals(keys, KeyRemap.silent(keys, 360.0F));
            assertEquals(keys, KeyRemap.nearest(keys, -720.0F, -1, 8.0F).keys());
        }
    }

    @Test
    void aQuarterTurnMovesEveryDirectionAQuarterAround() {
        assertEquals(D, KeyRemap.silent(W, 90.0F));
        assertEquals(D, KeyRemap.nearest(W, 90.0F, -1, 8.0F).keys());
        assertEquals(A, KeyRemap.silent(W, -90.0F));
        assertEquals(A, KeyRemap.nearest(W, -90.0F, -1, 8.0F).keys());
        // Left of a camera that points to the reported facing's right is the reported facing's forward.
        assertEquals(W, KeyRemap.nearest(A, 90.0F, -1, 8.0F).keys());
        assertEquals(S, KeyRemap.nearest(D, 90.0F, -1, 8.0F).keys());
    }

    @Test
    void facingTheOtherWayReversesTheKeys() {
        assertEquals(S, KeyRemap.silent(W, 180.0F));
        assertEquals(W, KeyRemap.silent(S, 180.0F));
        assertEquals(SD, KeyRemap.nearest(WA, 180.0F, -1, 8.0F).keys());
        assertEquals(SA, KeyRemap.nearest(WD, 180.0F, -1, 8.0F).keys());
    }

    @Test
    void anEighthOfATurnStepsOneDirectionAround() {
        assertEquals(WD, KeyRemap.nearest(W, 45.0F, -1, 8.0F).keys());
        assertEquals(W, KeyRemap.nearest(WA, 45.0F, -1, 8.0F).keys());
        assertEquals(WA, KeyRemap.nearest(W, -45.0F, -1, 8.0F).keys());
    }

    @Test
    void noKeyHeldIsNoDirection() {
        assertEquals(NONE, KeyRemap.silent(NONE, 90.0F));
        KeyRemap.Steered steered = KeyRemap.nearest(NONE, 90.0F, 2, 8.0F);
        assertSame(NONE, steered.keys());
        assertEquals(-1, steered.direction());
        // Opposite keys cancel out, as they do in the game.
        Input cancelling = new Input(true, true, false, false, false, false, false);
        assertSame(cancelling, KeyRemap.nearest(cancelling, 90.0F, -1, 8.0F).keys());
        assertEquals(cancelling, KeyRemap.silent(cancelling, 90.0F));
    }

    @Test
    void theOtherKeysAreLeftAlone() {
        Input held = new Input(true, false, false, false, true, true, true);
        Input turned = KeyRemap.nearest(held, 90.0F, -1, 8.0F).keys();
        assertTrue(turned.right() && !turned.forward(), "forward became right");
        assertTrue(turned.jump() && turned.shift() && turned.sprint());
        Input turnedSilent = KeyRemap.silent(held, 90.0F);
        assertTrue(turnedSilent.jump() && turnedSilent.shift() && turnedSilent.sprint());
    }

    @Test
    void theNearestDirectionGivesEveryDirectionTheSameWidth() {
        // Forward is kept up to 22.5 degrees off either way, and the diagonal takes over, on both sides alike.
        assertEquals(W, KeyRemap.nearest(W, 22.0F, -1, 8.0F).keys());
        assertEquals(W, KeyRemap.nearest(W, -22.0F, -1, 8.0F).keys());
        assertEquals(WD, KeyRemap.nearest(W, 23.0F, -1, 8.0F).keys());
        assertEquals(WA, KeyRemap.nearest(W, -23.0F, -1, 8.0F).keys());
        // ...and the diagonal keeps its 45 degrees.
        assertEquals(WD, KeyRemap.nearest(W, 67.0F, -1, 8.0F).keys());
        assertEquals(D, KeyRemap.nearest(W, 68.0F, -1, 8.0F).keys());
    }

    @Test
    void roundingEachImpulseGivesTheStraightDirectionsTwiceTheWidthOfTheDiagonals() {
        // LiquidBounce's rounding: forward to 30 degrees, the diagonal from there to 60, then right.
        assertEquals(W, KeyRemap.silent(W, 29.0F));
        assertEquals(WD, KeyRemap.silent(W, 31.0F));
        assertEquals(WD, KeyRemap.silent(W, 59.0F));
        assertEquals(D, KeyRemap.silent(W, 61.0F));
    }

    @Test
    void aDirectionIsHeldWhileTheAimOnlyWaversAroundTheEdge() {
        // 24 degrees is nearer the diagonal, but a walk that was going forward keeps going forward...
        KeyRemap.Steered held = KeyRemap.nearest(W, 24.0F, 0, 8.0F);
        assertEquals(W, held.keys());
        assertEquals(0, held.direction());
        // ...until the aim is more than 8 degrees past the edge.
        assertEquals(WD, KeyRemap.nearest(W, 31.0F, 0, 8.0F).keys());
        // With nothing to hold on to, the nearest is taken.
        assertEquals(WD, KeyRemap.nearest(W, 24.0F, -1, 8.0F).keys());
        // A direction that is not the one the aim is nearest to or next to it is never held.
        assertEquals(D, KeyRemap.nearest(W, 90.0F, 0, 8.0F).keys());
    }

    @Test
    void aDirectionIsFoundAndHeldAcrossTheSeamOfTheCircle() {
        // Back is direction 4, reached from either side of the +-180 seam.
        assertEquals(S, KeyRemap.nearest(W, 175.0F, -1, 8.0F).keys());
        assertEquals(S, KeyRemap.nearest(W, -175.0F, -1, 8.0F).keys());
        assertEquals(4, KeyRemap.nearest(W, -175.0F, -1, 8.0F).direction());
        // At -150 the nearest is the back-right diagonal, but back is only 30 degrees away round the seam - not 330.
        assertEquals(SD, KeyRemap.nearest(W, 150.0F, -1, 8.0F).keys());
        assertEquals(S, KeyRemap.nearest(W, 150.0F, 4, 8.0F).keys());
    }

    @Test
    void everyResultIsKeysAKeyboardCanMake() {
        for (Input keys : EIGHT) {
            for (int delta = -360; delta <= 360; delta += 7) {
                Input silent = KeyRemap.silent(keys, delta);
                Input nearest = KeyRemap.nearest(keys, delta, -1, 8.0F).keys();
                for (Input result : new Input[]{silent, nearest}) {
                    assertTrue(!(result.forward() && result.backward()) && !(result.left() && result.right()),
                            "opposite keys at delta " + delta + " for " + keys);
                }
                assertTrue(nearest.forward() || nearest.backward() || nearest.left() || nearest.right(), "a key is held");
            }
        }
    }

    /**
     * What {@link MovementCorrector#HYSTERESIS} says of the model: an aim that circles the player and wobbles a few degrees
     * a tick, with W held and the camera fixed, seen through both remaps (the same seed for both). It is a model - in the
     * game the price of the hysteresis was higher, which is why the constant is small.
     */
    @Test
    void inTheModelClaude3IsNoLessAccurateThanSilentAndChangesTheKeysLessOften() {
        double[] silent = sweep(false);
        double[] claude3 = sweep(true);
        // mean error, max error, key changes per tick
        assertTrue(claude3[0] < silent[0], "mean error " + claude3[0] + " vs " + silent[0]);
        assertTrue(claude3[1] < silent[1], "max error " + claude3[1] + " vs " + silent[1]);
        assertTrue(claude3[2] < 0.7 * silent[2], "key changes " + claude3[2] + " vs " + silent[2]);
    }

    private static double[] sweep(final boolean nearest) {
        Random random = new Random(3);
        float reported = 0.0F;
        int direction = -1;
        Input last = null;
        int changes = 0;
        double total = 0.0;
        double worst = 0.0;
        int ticks = 100_000;
        for (int tick = 0; tick < ticks; tick++) {
            reported += 1.5F + (float) (random.nextGaussian() * 4.0);
            float delta = -reported;
            Input keys;
            if (nearest) {
                KeyRemap.Steered steered = KeyRemap.nearest(W, delta, direction, MovementCorrector.HYSTERESIS);
                keys = steered.keys();
                direction = steered.direction();
            } else {
                keys = KeyRemap.silent(W, delta);
            }
            // The way W was meant to go, in the reported facing's frame, against the way the keys go in it.
            double meant = Math.toDegrees(Math.atan2(-Math.sin(Math.toRadians(delta)), Math.cos(Math.toRadians(delta))));
            double made = Math.toDegrees(Math.atan2((keys.left() ? 1 : 0) - (keys.right() ? 1 : 0),
                    (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0)));
            double error = Math.abs(Mth.wrapDegrees((float) (made - meant)));
            total += error;
            worst = Math.max(worst, error);
            if (last != null && !keys.equals(last)) {
                changes++;
            }
            last = keys;
        }
        return new double[]{total / ticks, worst, (double) changes / ticks};
    }
}
