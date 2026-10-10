package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Exercises the bundled nameplate icons through the same SVG rasterizer used by the HUD. */
class ModernNameTagIconsTest {
    @Test
    void everyNameTagIconDrawsVisibleLinesWithoutFillingItsTile() throws IOException {
        int size = 48;
        for (String name : new String[]{"heart", "distance", "shield", "signal", "suspect"}) {
            String resource = "/assets/minecraft/sigma/icons/nametags/" + name + ".svg";
            ModernSkiaRaster.Image image;
            try (InputStream stream = ModernNameTagIconsTest.class.getResourceAsStream(resource)) {
                assertNotNull(stream, "Missing nameplate icon: " + resource);
                image = ModernSvg.rasterize(stream.readAllBytes(), size, size);
            }
            int covered = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if ((image.getRGB(x, y) >>> 24) > 128) covered++;
                }
            }
            assertTrue(covered > size * size / 20 && covered < size * size / 2,
                    name + " should render visible line art, but covers " + covered + " pixels");
        }
    }
}
