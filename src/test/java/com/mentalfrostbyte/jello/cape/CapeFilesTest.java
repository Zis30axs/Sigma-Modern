package com.mentalfrostbyte.jello.cape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CapeFilesTest {

    @Test
    void onlyTwoToOneImagesOfAtLeastVanillaSizeAreCapes() {
        assertTrue(CapeFiles.isValidSize(64, 32));
        assertTrue(CapeFiles.isValidSize(128, 64));
        assertTrue(CapeFiles.isValidSize(2048, 1024));
        assertFalse(CapeFiles.isValidSize(64, 64), "a skin, not a cape");
        assertFalse(CapeFiles.isValidSize(32, 16), "smaller than one texel per cape pixel");
        assertFalse(CapeFiles.isValidSize(100, 32));
        assertFalse(CapeFiles.isValidSize(CapeFiles.MAX_WIDTH * 2, CapeFiles.MAX_WIDTH));
    }

    @Test
    void onlyVisiblePngFilesAreRead() {
        assertTrue(CapeFiles.isCapeFile("cape.png"));
        assertTrue(CapeFiles.isCapeFile("My Cape.PNG"));
        assertFalse(CapeFiles.isCapeFile("cape.jpg"));
        assertFalse(CapeFiles.isCapeFile(".png"));
        assertFalse(CapeFiles.isCapeFile(".hidden.png"));
    }

    @Test
    void customIdsAreTheFileNameAndNamesDropTheExtension() {
        assertEquals("file:Red Cape.png", CapeFiles.idFor("Red Cape.png"));
        assertTrue(CapeFiles.isCustomId(CapeFiles.idFor("x.png")));
        assertFalse(CapeFiles.isCustomId("sigma"));
        assertEquals("Red Cape", CapeFiles.displayName("Red Cape.png"));
    }

    @Test
    void texturePathsAreValidResourcePathsAndNeverCollide() {
        String a = CapeFiles.texturePath(0, "Red Cape!.png");
        String b = CapeFiles.texturePath(1, "red_cape_.png");
        assertTrue(a.matches("[a-z0-9/._-]+"), a);
        assertTrue(b.matches("[a-z0-9/._-]+"), b);
        assertNotEquals(a, b);
        assertTrue(CapeFiles.texturePath(2, "披风.png").matches("[a-z0-9/._-]+"));
    }
}
