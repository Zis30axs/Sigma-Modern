package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.lang.ClientLanguage;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** The bundled SVGs through Skia's SVG renderer, as the ClickGUI draws them. Needs Skija's native library. */
class ModernSvgTest {

    private static ModernSkiaRaster.Image render(String resource, int width, int height) throws IOException {
        try (InputStream stream = ModernSvgTest.class.getResourceAsStream(resource)) {
            return ModernSvg.rasterize(stream.readAllBytes(), width, height);
        }
    }

    @Test
    void everyCategoryIconDrawsSomethingButNotABlock() throws IOException {
        for (ModuleCategory category : ModuleCategory.values()) {
            ModernSkiaRaster.Image icon = render(ModernSvg.categoryIcon(category), 48, 48);
            int covered = 0;
            for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) if ((icon.getRGB(x, y) >>> 24) > 128) covered++;
            // Line icons: well inked, but mostly empty.
            assertTrue(covered > 48 * 48 / 20 && covered < 48 * 48 / 2, category + " covers " + covered + " pixels");
        }
    }

    @Test
    void theFlagsHaveTheirColoursWhereTheyShould() throws IOException {
        ModernSkiaRaster.Image japan = render(ClientLanguage.JA_JP.flagResource(), 60, 40);
        assertColor(0xFFBC002D, japan.getRGB(30, 20), "Japan: the disc");
        assertColor(0xFFFFFFFF, japan.getRGB(3, 3), "Japan: the field");

        ModernSkiaRaster.Image russia = render(ClientLanguage.RU_RU.flagResource(), 60, 40);
        assertColor(0xFFFFFFFF, russia.getRGB(30, 5), "Russia: white");
        assertColor(0xFF0039A6, russia.getRGB(30, 20), "Russia: blue");
        assertColor(0xFFD52B1E, russia.getRGB(30, 35), "Russia: red");

        ModernSkiaRaster.Image china = render(ClientLanguage.ZH_CN.flagResource(), 60, 40);
        assertColor(0xFFFFFF00, china.getRGB(10, 10), "China: the big star");
        assertColor(0xFFEE1C25, china.getRGB(50, 30), "China: the field");

        ModernSkiaRaster.Image usa = render(ClientLanguage.EN_US.flagResource(), 60, 40);
        assertColor(0xFFB22234, usa.getRGB(50, 1), "US: the top stripe is red");
        assertColor(0xFF3C3B6E, usa.getRGB(0, 0), "US: the canton, clear of the stars");

        ModernSkiaRaster.Image spain = render(ClientLanguage.ES_ES.flagResource(), 60, 40);
        assertColor(0xFFF1BF00, spain.getRGB(30, 20), "Spain: yellow in the middle");

        ModernSkiaRaster.Image korea = render(ClientLanguage.KO_KR.flagResource(), 60, 40);
        assertColor(0xFFCD2E3A, korea.getRGB(33, 13), "Korea: red above the diagonal");
        assertColor(0xFF0047A0, korea.getRGB(27, 27), "Korea: blue below it");
    }

    private static void assertColor(int expected, int actual, String what) {
        for (int shift = 0; shift <= 24; shift += 8) {
            int e = expected >>> shift & 0xFF, a = actual >>> shift & 0xFF;
            assertTrue(Math.abs(e - a) <= 6, what + ": expected " + Integer.toHexString(expected) + ", got " + Integer.toHexString(actual));
        }
        assertEquals(0xFF, actual >>> 24, what + " should be opaque");
    }
}
