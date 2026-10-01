package com.mentalfrostbyte.jello.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkColoursTest {

    @Test
    void blendingMovesEachChannelPartOfTheWayAndNothingMoreThanThat() {
        assertEquals(0x7FB238, ChunkColours.blend(0x7FB238, 0x000000, 0.0F));
        assertEquals(0x000000, ChunkColours.blend(0x7FB238, 0x000000, 1.0F));
        // 60 % of the way to black leaves 40 % of each channel: 127 * 0.4 = 51, 178 * 0.4 = 71, 56 * 0.4 = 22.
        assertEquals(0x334716, ChunkColours.blend(0x7FB238, 0x000000, 0.6F));
        assertEquals(0xFFFFFF, ChunkColours.blend(0x7FB238, 0xFFFFFF, 1.0F));
    }
}
