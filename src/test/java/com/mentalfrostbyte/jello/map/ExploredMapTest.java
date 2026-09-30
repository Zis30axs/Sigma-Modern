package com.mentalfrostbyte.jello.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExploredMapTest {
    @TempDir
    Path dir;

    private static int[] fill(final int rgb) {
        int[] chunk = new int[256];
        Arrays.fill(chunk, 0xFF000000 | rgb);
        return chunk;
    }

    @Test
    void aChunkIsGivenBackAsItWasPut() {
        ExploredMap map = new ExploredMap(this.dir);
        assertNull(map.get(3, -4));
        assertFalse(map.has(3, -4));

        map.put(3, -4, fill(0x112233));
        assertArrayEquals(fill(0x112233), map.get(3, -4));
        assertTrue(map.has(3, -4));
        assertNull(map.get(4, -4));
    }

    @Test
    void chunksOnEitherSideOfTheOriginAreKeptApart() {
        ExploredMap map = new ExploredMap(this.dir);
        map.put(-1, -1, fill(0x010101));
        map.put(0, 0, fill(0x020202));
        map.put(-8, 7, fill(0x030303));
        map.put(8, -9, fill(0x040404));
        assertEquals(0xFF010101, map.get(-1, -1)[0]);
        assertEquals(0xFF020202, map.get(0, 0)[0]);
        assertEquals(0xFF030303, map.get(-8, 7)[0]);
        assertEquals(0xFF040404, map.get(8, -9)[0]);
    }

    @Test
    void whatWasFlushedIsReadBackByAnotherMapOverTheSameFolder() {
        ExploredMap map = new ExploredMap(this.dir.resolve("overworld"));
        int[] varied = new int[256];
        for (int i = 0; i < 256; i++) {
            varied[i] = 0xFF000000 | i * 0x010203 & 0xFFFFFF;
        }

        map.put(-9, 20, varied);
        map.put(5, 5, fill(0x7FB238));
        assertTrue(map.dirty());
        map.flush();
        assertFalse(map.dirty());

        ExploredMap again = new ExploredMap(this.dir.resolve("overworld"));
        assertArrayEquals(varied, again.get(-9, 20));
        assertArrayEquals(fill(0x7FB238), again.get(5, 5));
        assertNull(again.get(6, 5));
    }

    @Test
    void oneFileHoldsARegionOfEightByEightChunks() throws IOException {
        ExploredMap map = new ExploredMap(this.dir);
        map.put(0, 0, fill(1));
        map.put(7, 7, fill(2));
        map.put(8, 0, fill(3));
        map.put(-1, 0, fill(4));
        map.flush();
        try (Stream<Path> files = Files.list(this.dir)) {
            assertEquals(3, files.filter(f -> f.getFileName().toString().endsWith(".jmap")).count());
        }
        assertTrue(Files.exists(this.dir.resolve("r.0.0.jmap")));
        assertTrue(Files.exists(this.dir.resolve("r.1.0.jmap")));
        assertTrue(Files.exists(this.dir.resolve("r.-1.0.jmap")));
    }

    @Test
    void sayingWhatIsAlreadyKnownChangesNothingAndAlphaIsNotKept() {
        ExploredMap map = new ExploredMap(this.dir);
        map.put(1, 1, fill(0x445566));
        int version = map.version();
        map.flush();

        map.put(1, 1, fill(0x445566));
        // The same colours with a see-through alpha are the same colours.
        int[] translucent = fill(0x445566);
        for (int i = 0; i < 256; i++) translucent[i] &= 0x00FFFFFF;
        map.put(1, 1, translucent);
        assertEquals(version, map.version());
        assertFalse(map.dirty());

        map.put(1, 1, fill(0x445567));
        assertEquals(version + 1, map.version());
        assertTrue(map.dirty());
    }

    @Test
    void aDamagedRegionFileIsAnEmptyRegionAndIsReplacedWhenSomethingIsSeen() throws IOException {
        Files.write(this.dir.resolve("r.0.0.jmap"), new byte[]{1, 2, 3, 4, 5});
        ExploredMap map = new ExploredMap(this.dir);
        assertNull(map.get(0, 0));

        map.put(0, 0, fill(0xABCDEF));
        map.flush();
        assertArrayEquals(fill(0xABCDEF), new ExploredMap(this.dir).get(0, 0));
    }

    @Test
    void composingLaysChunksOutRowByRowWithTheUnseenOnesBlue() {
        ExploredMap map = new ExploredMap(this.dir);
        int[] chunk = new int[256];
        for (int i = 0; i < 256; i++) chunk[i] = 0xFF000000 | i;
        map.put(2, -3, chunk);

        // A 2 x 2 window whose top-left chunk is (1, -3): the seen chunk is the top-right one.
        int[] out = new int[32 * 32];
        map.compose(1, -3, 2, 2, out);
        assertEquals(ExploredMap.UNKNOWN, out[0]);
        assertEquals(ExploredMap.UNKNOWN, out[15]);
        assertEquals(chunk[0], out[16]);
        assertEquals(chunk[15], out[31]);
        // The chunk's second row starts 32 pixels (one picture row) further on.
        assertEquals(chunk[16], out[32 + 16]);
        assertEquals(chunk[255], out[15 * 32 + 31]);
        // Nothing below it was seen.
        assertEquals(ExploredMap.UNKNOWN, out[16 * 32 + 16]);
    }

    @Test
    void theRightSizeIsRequired() {
        ExploredMap map = new ExploredMap(this.dir);
        assertThrows(IllegalArgumentException.class, () -> map.put(0, 0, new int[255]));
        assertThrows(IllegalArgumentException.class, () -> map.compose(0, 0, 2, 2, new int[10]));
    }

    @Test
    void readingFarMoreRegionsThanAreKeptStillGivesEveryChunkBack() {
        ExploredMap map = new ExploredMap(this.dir);
        // 200 regions in a row: more than the map keeps in memory, so the older ones are written out and read back.
        for (int r = 0; r < 200; r++) {
            map.put(r * 8, 0, fill(r));
        }
        for (int r = 0; r < 200; r++) {
            assertEquals(0xFF000000 | r, map.get(r * 8, 0)[0], "region " + r);
        }
    }
}
