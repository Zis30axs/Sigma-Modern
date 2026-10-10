package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ModernBloomTest {
    @Test
    void gaussianMaskHasASoftBoundaryAndTransparentPadding() {
        ModernBloom.Key key = ModernBloom.key(90, 12, 3, 2);
        ModernSkiaRaster.Image image = ModernBloom.mask(key);
        int middleY = image.height() / 2;
        int center = alpha(image, image.width() / 2, middleY);
        int edge = alpha(image, ModernBloom.PADDING * key.density(), middleY);
        int outside = alpha(image, (ModernBloom.PADDING - 3) * key.density(), middleY);
        assertTrue(center > 220, "the row's center must retain enough glow coverage");
        assertTrue(edge > outside && edge < center && outside > 0, "coverage must fade smoothly beyond the edge");
        assertTrue(alpha(image, 0, middleY) <= 2, "padding must end essentially transparent");
        assertEquals(0, alpha(image, 0, 0));
        for (int x = 0; x < image.width() / 2; x++) {
            assertEquals(alpha(image, x, middleY), alpha(image, image.width() - 1 - x, middleY), 2,
                    "left and right edges should have the same falloff");
        }
    }

    @Test
    void narrowAndShortShapesAreBlurredWithoutNegativeSlicesOrHardCenters() {
        for (int[] dimensions : new int[][]{{1, 1}, {2, 12}, {30, 1}, {7, 5}}) {
            ModernBloom.Key key = ModernBloom.key(dimensions[0], dimensions[1], 32, 2);
            ModernSkiaRaster.Image image = ModernBloom.mask(key);
            int center = alpha(image, image.width() / 2, image.height() / 2);
            assertTrue(center > 0 && center < 255, "small shapes must remain soft and nonempty");
            assertTrue(key.width() <= dimensions[0] && key.height() <= dimensions[1]);
            assertTrue(key.radius() <= Math.min(dimensions[0], dimensions[1]) / 2);
            int capX = Math.min(key.width() / 2, key.radius() + ModernBloom.PADDING);
            int capY = Math.min(key.height() / 2, key.radius() + ModernBloom.PADDING);
            assertTrue(dimensions[0] - capX * 2 >= 0 && dimensions[1] - capY * 2 >= 0);
            assertEquals(0, alpha(image, 0, 0));
        }
    }

    @Test
    void longRowsShareSmallMasksAndEveryKeyHasBoundedDimensions() {
        assertEquals(ModernBloom.key(80, 11, 3, 2), ModernBloom.key(800, 11, 3, 2));
        ModernBloom.Key extreme = ModernBloom.key(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertTrue(extreme.pixelWidth() <= 400 && extreme.pixelHeight() <= 400);
        assertTrue(extreme.bytes() <= 1_280_000L);
        assertEquals(1, ModernBloom.key(1, 1, -2, 0).density());
    }

    @Test
    void strengthAndAnimationFadeMultiplyOnceWithoutChangingTint() {
        assertEquals(0x204488CC, ModernBloom.tint(0x804488CC, 0.5F, 0.5F));
        assertEquals(0x004488CC, ModernBloom.tint(0xFF4488CC, 0F, 1F));
        assertEquals(0x004488CC, ModernBloom.tint(0xFF4488CC, 1F, 0F));
        assertEquals(0x804488CC, ModernBloom.tint(0x804488CC, 9F, 9F));
        assertEquals(0x004488CC, ModernBloom.tint(0xFF4488CC, Float.NaN, 1F));
    }

    @Test
    void aFullCacheNeverClosesTexturesReferencedByCurrentOrRecentFrames() {
        ModernBloom.Cache cache = new ModernBloom.Cache(1, 100);
        ModernBloom.Key first = ModernBloom.key(30, 11, 3, 1);
        Identifier id = Identifier.withDefaultNamespace("bloom_test/first");
        List<Identifier> released = new ArrayList<>();
        cache.put(first, new ModernBloom.Texture(id, 80, 7));
        assertFalse(cache.makeRoom(20, 7, released::add));
        assertFalse(cache.makeRoom(20, 9, released::add));
        assertTrue(released.isEmpty());
        assertEquals(1, cache.size());
        assertEquals(80, cache.bytes());
        assertTrue(cache.makeRoom(20, 10, released::add));
        assertEquals(List.of(id), released);
        assertNull(cache.get(first, 10));
        assertEquals(0, cache.bytes());
    }

    @Test
    void memoryPressureEvictsOnlyOldEntriesAndUsagePinsTheSurvivor() {
        ModernBloom.Cache cache = new ModernBloom.Cache(3, 100);
        ModernBloom.Key first = ModernBloom.key(20, 11, 3, 1);
        ModernBloom.Key second = ModernBloom.key(21, 11, 3, 1);
        Identifier old = Identifier.withDefaultNamespace("bloom_test/old");
        Identifier live = Identifier.withDefaultNamespace("bloom_test/live");
        cache.put(first, new ModernBloom.Texture(old, 40, 1));
        cache.put(second, new ModernBloom.Texture(live, 40, 1));
        assertNotNull(cache.get(second, 5));
        List<Identifier> released = new ArrayList<>();
        assertTrue(cache.makeRoom(50, 5, released::add));
        assertEquals(List.of(old), released);
        assertEquals(40, cache.bytes());
        assertFalse(cache.makeRoom(101, 10, released::add), "oversized requests cannot grow the cache");
        assertNotNull(cache.get(second, 10));
        assertEquals(1, released.size());
    }

    private static int alpha(ModernSkiaRaster.Image image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }
}
