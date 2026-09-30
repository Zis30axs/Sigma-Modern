package com.mentalfrostbyte.jello.gui.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The size in {@link LegacyTexture} is what a blit slices the file by, so a wrong one draws garbage. */
class LegacyTextureTest {
    private static final String ROOT = "/assets/minecraft/";

    @Test
    void everyTextureIsTheSizeItIsDeclaredAs() throws Exception {
        for (LegacyTexture texture : LegacyTexture.values()) {
            try (InputStream in = LegacyTextureTest.class.getResourceAsStream(ROOT + texture.id.getPath())) {
                assertNotNull(in, "missing " + texture.id);
                BufferedImage image = ImageIO.read(in);
                assertEquals(texture.width, image.getWidth(), texture + " width");
                assertEquals(texture.height, image.getHeight(), texture + " height");
            }
        }
    }

    @Test
    void everyTextureAsksForLinearFiltering() throws Exception {
        for (LegacyTexture texture : LegacyTexture.values()) {
            try (InputStream in = LegacyTextureTest.class.getResourceAsStream(ROOT + texture.id.getPath() + ".mcmeta")) {
                assertNotNull(in, "no .mcmeta for " + texture.id);
                String meta = new String(in.readAllBytes());
                assertEquals(true, meta.contains("\"blur\":true"), texture + " should be drawn with linear filtering");
            }
        }
    }
}
