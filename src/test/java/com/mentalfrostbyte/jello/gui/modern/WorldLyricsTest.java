package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorldLyricsTest {
    @Test void alternatingPhrasesStayAheadOfThePlayerAtCardinalHeadings() {
        var eye = new Vec3(70, 91, -36);
        Vec3 left = WorldLyrics.target(eye, 0, 1).subtract(eye);
        Vec3 right = WorldLyrics.target(eye, 0, 2).subtract(eye);
        assertEquals(6, right.z, 1e-6);
        assertEquals(-right.x, left.x, 1e-6);
        assertEquals(-0.4, left.y, 1e-6);
        Vec3 west = WorldLyrics.target(eye, 90, 2).subtract(eye);
        assertEquals(-right.z, west.x, 1e-6);
        assertEquals(right.x, west.z, 1e-6);
    }

    @Test void singingAnAstralCharacterNeverSplitsTheSurrogatePair() {
        String text = "夢🌙光";
        assertEquals(1, WorldLyrics.boundary(text, .5F));
        assertEquals(3, WorldLyrics.boundary(text, .75F));
        assertEquals(4, WorldLyrics.boundary(text, 1F));
    }

    @Test void wrappedLineProgressSweepsSequentiallyAcrossLines() {
        assertEquals(0.5F, WorldLyrics.lineProgress(0.25F, 0F, 100F, 200F), 1e-5F);
        assertEquals(0F, WorldLyrics.lineProgress(0.25F, 100F, 100F, 200F), 1e-5F);
        assertEquals(1F, WorldLyrics.lineProgress(0.75F, 0F, 100F, 200F), 1e-5F);
        assertEquals(0.5F, WorldLyrics.lineProgress(0.75F, 100F, 100F, 200F), 1e-5F);
    }

    @Test void retiringCaptionRecedesAwayFromCamera() {
        var cam = new Vec3(0, 64, 0);
        var anchor = new Vec3(0, 64, 3);
        Vec3 moved = WorldLyrics.recedeFrom(anchor, cam, 0.34F);
        assertEquals(3.34, moved.z, 1e-5);
        assertEquals(anchor, WorldLyrics.recedeFrom(cam, cam, 0.34F));
    }

    @Test void movementExtendsTheGenerationDistanceWithoutExceedingTenBlocks() {
        assertEquals(6.0, WorldLyrics.generationDistance(0.0), 1e-6);
        assertEquals(8.8, WorldLyrics.generationDistance(5.6), 1e-6);
        assertEquals(10.0, WorldLyrics.generationDistance(20.0), 1e-6);
        assertEquals(6.0, WorldLyrics.generationDistance(-1.0), 1e-6);
        assertEquals(6.0, WorldLyrics.generationDistance(Double.NaN), 1e-6);
    }

    @Test void nearbyWallsNeverPlaceALyricInsideTheSafetyDistance() {
        var eye = new Vec3(10, 64, 20);
        assertNull(WorldLyrics.beforeObstacle(eye, eye));
        assertNull(WorldLyrics.beforeObstacle(eye, eye.add(0, 0, 1)));
        assertNull(WorldLyrics.beforeObstacle(eye, eye.add(0, 0, 2.74)));
        assertEquals(eye.add(0, 0, 2.5), WorldLyrics.beforeObstacle(eye, eye.add(0, 0, 2.75)));
        assertEquals(eye.add(0, 0, 6.75), WorldLyrics.beforeObstacle(eye, eye.add(0, 0, 7)));
    }

    @Test void slowFramesDoNotLookLikeSeekingDuringContinuousPlayback() {
        assertFalse(WorldLyrics.playbackJumped(1_000L, 3_000L, 2_000_000_000L));
        assertFalse(WorldLyrics.playbackJumped(1_000L, 1_016L, 16_000_000L));
        assertTrue(WorldLyrics.playbackJumped(1_000L, 5_000L, 16_000_000L));
        assertTrue(WorldLyrics.playbackJumped(1_000L, 900L, 16_000_000L));
    }
}
