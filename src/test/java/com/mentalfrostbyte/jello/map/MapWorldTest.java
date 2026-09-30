package com.mentalfrostbyte.jello.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MapWorldTest {

    @Test
    void anAddressBecomesOneFolderName() {
        assertEquals("play.example.com_25565", MapWorld.safe("play.example.com:25565"));
        assertEquals("a_b_c", MapWorld.safe("a/b\\c"));
        assertEquals("My_World", MapWorld.safe("My World"));
    }

    @Test
    void namesInOtherScriptsKeepTheirLetters() {
        assertEquals("新世界", MapWorld.safe("新世界"));
        assertEquals("Welt_1", MapWorld.safe("Welt 1"));
    }

    @Test
    void theNameCanNeverEscapeItsFolderOrBeEmpty() {
        for (String raw : new String[]{"", ".", "..", "...", "../..", ".hidden", "a.", "CON."}) {
            String safe = MapWorld.safe(raw);
            assertFalse(safe.isEmpty(), raw);
            assertFalse(safe.startsWith("."), raw);
            assertFalse(safe.endsWith("."), raw);
            assertFalse(safe.contains("/") || safe.contains("\\"), raw);
        }
    }

    @Test
    void aLongNameIsCut() {
        assertEquals(64, MapWorld.safe("x".repeat(500)).length());
    }

    @Test
    void aDimensionIsItsPathUnlessItComesFromAnotherNamespace() {
        assertEquals("overworld", MapWorld.dimensionFolder("minecraft:overworld"));
        assertEquals("the_nether", MapWorld.dimensionFolder("minecraft:the_nether"));
        assertEquals("mymod_caves", MapWorld.dimensionFolder("mymod:caves"));
        assertTrue(MapWorld.dimensionFolder("minecraft:overworld").matches("[a-z_]+"));
    }
}
