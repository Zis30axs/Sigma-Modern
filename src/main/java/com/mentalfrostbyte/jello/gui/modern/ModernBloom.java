package com.mentalfrostbyte.jello.gui.modern;

import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.RRect;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Small Gaussian coverage masks, tinted at draw time and stretched only through their flat middle. */
final class ModernBloom {
    static final float SIGMA = 2.5F;
    static final int PADDING = 8;
    private static final Cache CACHE = new Cache(64, 8L * 1024 * 1024);
    private static @Nullable GuiGraphicsExtractor lastExtractor;
    private static long frame;
    private static long serial;

    private ModernBloom() {}

    /**
     * A soft glow behind a rounded rectangle. Like ModernTypography, called by the main GUI extraction
     * on the render thread; changing position, tint or fade never creates another mask.
     */
    static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color, float strength) {
        color = tint(color, strength, ModernStyle.groupAlpha());
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        if (lastExtractor != g) {
            lastExtractor = g;
            frame++;
        }
        float transform = Math.max((float)Math.hypot(g.pose().m00(), g.pose().m01()),
                (float)Math.hypot(g.pose().m10(), g.pose().m11()));
        int density = Math.clamp((int)Math.ceil(transform * Minecraft.getInstance().getWindow().getGuiScale()), 1, 4);
        Key key = key(w, h, radius, density);
        Texture texture = CACHE.get(key, frame);
        if (texture == null) {
            // Deferred GUI draws still own current/recent textures. If all slots are pinned, leave out
            // this new glow rather than exceed the bound or close something already submitted.
            if (!CACHE.makeRoom(key.bytes(), frame, ModernSkiaRaster::release)) return;
            Identifier id = Identifier.withDefaultNamespace("sigma/modern_bloom/" + serial++);
            ModernSkiaRaster.upload(id, mask(key));
            texture = new Texture(id, key.bytes(), frame);
            CACHE.put(key, texture);
        }

        int capX = Math.min(key.width / 2, key.radius + PADDING);
        int capY = Math.min(key.height / 2, key.radius + PADDING);
        int[] sourceX = {0, PADDING + capX, PADDING + key.width - capX, key.width + 2 * PADDING};
        int[] sourceY = {0, PADDING + capY, PADDING + key.height - capY, key.height + 2 * PADDING};
        int[] targetX = {x - PADDING, x + capX, x + w - capX, x + w + PADDING};
        int[] targetY = {y - PADDING, y + capY, y + h - capY, y + h + PADDING};
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                int width = targetX[column + 1] - targetX[column];
                int height = targetY[row + 1] - targetY[row];
                int sourceWidth = sourceX[column + 1] - sourceX[column];
                int sourceHeight = sourceY[row + 1] - sourceY[row];
                if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) continue;
                g.blit(RenderPipelines.GUI_TEXTURED, texture.id, targetX[column], targetY[row],
                        sourceX[column] * density, sourceY[row] * density, width, height,
                        sourceWidth * density, sourceHeight * density, key.pixelWidth(), key.pixelHeight(), color);
            }
        }
    }

    /** Preserve RGB and multiply all three alpha factors exactly once. */
    static int tint(int color, float strength, float groupAlpha) {
        if (!Float.isFinite(strength) || !Float.isFinite(groupAlpha)) return color & 0x00FFFFFF;
        int alpha = Math.round((color >>> 24) * Math.clamp(strength, 0F, 1F) * Math.clamp(groupAlpha, 0F, 1F));
        return alpha << 24 | color & 0x00FFFFFF;
    }

    /** A canonical source has a two-pixel middle; narrow/short shapes keep their exact dimensions. */
    static Key key(int width, int height, int radius, int density) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Bloom dimensions must be positive");
        int r = Math.clamp(radius, 0, Math.min(32, Math.min(width, height) / 2));
        int limit = 2 * (r + PADDING) + 2;
        return new Key(Math.min(width, limit), Math.min(height, limit), r, Math.clamp(density, 1, 4));
    }

    /** CPU-only rasterization, also used by the mask regression tests. */
    static ModernSkiaRaster.Image mask(Key key) {
        return ModernSkiaRaster.render(key.pixelWidth(), key.pixelHeight(), canvas -> {
            try (MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, SIGMA);
                 Paint paint = new Paint().setColor(0xFFFFFFFF).setAntiAlias(true).setMaskFilter(blur)) {
                canvas.scale(key.density, key.density);
                canvas.drawRRect(RRect.makeXYWH(PADDING, PADDING, key.width, key.height, key.radius), paint);
            }
        });
    }

    record Key(int width, int height, int radius, int density) {
        int pixelWidth() { return (width + 2 * PADDING) * density; }
        int pixelHeight() { return (height + 2 * PADDING) * density; }
        // DynamicTexture keeps its NativeImage alongside the GPU image.
        long bytes() { return (long)pixelWidth() * pixelHeight() * 8; }
    }

    static final class Texture {
        final Identifier id;
        final long bytes;
        long lastFrame;

        Texture(Identifier id, long bytes, long lastFrame) {
            this.id = id;
            this.bytes = bytes;
            this.lastFrame = lastFrame;
        }
    }

    /** Bounded LRU bookkeeping; release is injected so the deferred-draw rule can be tested without a GPU. */
    static final class Cache {
        private final int maxEntries;
        private final long maxBytes;
        private final Map<Key, Texture> entries = new LinkedHashMap<>(16, 0.75F, true);
        private long bytes;

        Cache(int maxEntries, long maxBytes) {
            this.maxEntries = maxEntries;
            this.maxBytes = maxBytes;
        }

        @Nullable Texture get(Key key, long currentFrame) {
            Texture texture = entries.get(key);
            if (texture != null) texture.lastFrame = currentFrame;
            return texture;
        }

        boolean makeRoom(long requiredBytes, long currentFrame, Consumer<Identifier> release) {
            if (requiredBytes > maxBytes || maxEntries <= 0) return false;
            Iterator<Texture> iterator = entries.values().iterator();
            while ((entries.size() >= maxEntries || bytes > maxBytes - requiredBytes) && iterator.hasNext()) {
                Texture texture = iterator.next();
                if (currentFrame - texture.lastFrame <= 2) continue;
                release.accept(texture.id);
                bytes -= texture.bytes;
                iterator.remove();
            }
            return entries.size() < maxEntries && bytes <= maxBytes - requiredBytes;
        }

        void put(Key key, Texture texture) {
            if (entries.containsKey(key) || entries.size() >= maxEntries || bytes > maxBytes - texture.bytes)
                throw new IllegalStateException("Bloom cache capacity was not reserved");
            entries.put(key, texture);
            bytes += texture.bytes;
        }

        int size() { return entries.size(); }
        long bytes() { return bytes; }
    }
}
