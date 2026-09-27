package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The status-effect icons PotionStatus draws: one per effect in the game's registry, each a line drawing. Set
 * {@code -Dsigma.test.effectSheet=<file.png>} to also get every icon on one sheet, to look them over. Needs Skija's
 * native library.
 */
class ModernEffectIconsTest {

    private static final Path EFFECTS = Path.of("src/main/java/net/minecraft/world/effect/MobEffects.java");

    /** Every effect the game registers, read off the registry's source so a new effect without an icon fails here. */
    private static List<String> registeredEffects() throws IOException {
        Matcher register = Pattern.compile("register\\(\\s*\"([a-z0-9_]+)\"").matcher(Files.readString(EFFECTS));
        TreeSet<String> ids = new TreeSet<>();
        while (register.find()) ids.add(register.group(1));
        return new ArrayList<>(ids);
    }

    private static byte[] file(String resource) throws IOException {
        try (InputStream stream = ModernEffectIconsTest.class.getResourceAsStream(resource)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    @Test
    void everyEffectHasItsOwnIcon() throws IOException {
        List<String> effects = registeredEffects();
        assertTrue(effects.size() >= 40, "read " + effects.size() + " effects off " + EFFECTS);
        for (String effect : effects) {
            assertEquals("/assets/minecraft/sigma/icons/effect/" + effect + ".svg", ModernSvg.effectIcon(effect));
            assertTrue(file(ModernSvg.effectIcon(effect)) != null, effect + " has no icon");
        }
    }

    @Test
    void anEffectWithoutAnIconGetsTheBottle() {
        assertEquals("/assets/minecraft/sigma/icons/effect/generic.svg", ModernSvg.effectIcon("from_some_datapack"));
        assertEquals("/assets/minecraft/sigma/icons/effect/generic.svg", ModernSvg.effectIcon("../category/combat"));
    }

    @Test
    void everyIconDrawsSomethingButNotABlock() throws IOException {
        List<String> effects = registeredEffects();
        effects.add("generic");
        List<ModernSkiaRaster.Image> sheet = new ArrayList<>();
        for (String effect : effects) {
            ModernSkiaRaster.Image icon = ModernSvg.rasterize(file("/assets/minecraft/sigma/icons/effect/" + effect + ".svg"), 48, 48);
            sheet.add(icon);
            int covered = 0;
            boolean edge = false;
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 48; x++) {
                    if ((icon.getRGB(x, y) >>> 24) <= 128) continue;
                    covered++;
                    edge |= x == 0 || y == 0 || x == 47 || y == 47;
                }
            }
            // Line icons like the category ones: well inked, mostly empty, and not cut off by the box.
            assertTrue(covered > 48 * 48 / 20 && covered < 48 * 48 / 2, effect + " covers " + covered + " pixels");
            assertFalse(edge, effect + " runs off the edge of its box");
        }
        String out = System.getProperty("sigma.test.effectSheet");
        if (out != null) writeSheet(new File(out), effects, sheet);
    }

    /** White-on-dark, 8 to a row, in {@code effects} order (alphabetical, the bottle last). */
    private static void writeSheet(File out, List<String> effects, List<ModernSkiaRaster.Image> icons) throws IOException {
        int cell = 64, columns = 8, rows = (icons.size() + columns - 1) / columns;
        BufferedImage sheet = new BufferedImage(columns * cell, rows * cell, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < sheet.getHeight(); y++) for (int x = 0; x < sheet.getWidth(); x++) sheet.setRGB(x, y, 0x202020);
        for (int i = 0; i < icons.size(); i++) {
            ModernSkiaRaster.Image icon = icons.get(i);
            int ox = i % columns * cell + (cell - icon.width()) / 2, oy = i / columns * cell + (cell - icon.height()) / 2;
            for (int y = 0; y < icon.height(); y++) {
                for (int x = 0; x < icon.width(); x++) {
                    int alpha = icon.getRGB(x, y) >>> 24;
                    int shade = 0x20 + (0xFF - 0x20) * alpha / 255;
                    sheet.setRGB(ox + x, oy + y, shade << 16 | shade << 8 | shade);
                }
            }
        }
        ImageIO.write(sheet, "png", out);
        System.out.println("Effect sheet (" + String.join(", ", effects) + ") -> " + out.getAbsolutePath());
    }
}
