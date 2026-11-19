package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorldLyricsTest {
    @Test void alternatingPhrasesStayAheadOfThePlayerAtCardinalHeadings() {
        var eye = new Vec3(70, 91, -36);
        Vec3 left = WorldLyrics.target(eye, 0, 1).subtract(eye);
        Vec3 right = WorldLyrics.target(eye, 0, 2).subtract(eye);
        assertEquals(3, right.z, 1e-6);
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
}
