package com.mentalfrostbyte.jello.gui.modern;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import java.util.function.Consumer;

/** Skia coverage masks, uploaded only on cache misses through Minecraft's device abstraction. */
final class ModernSkiaRaster {
    private ModernSkiaRaster() {}

    static Image render(int width, int height, Consumer<Canvas> draw) {
        try (Surface surface = Surface.makeRaster(new ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL));
             Bitmap bitmap = new Bitmap()) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0);
            draw.accept(canvas);
            ImageInfo info = new ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
            if (!bitmap.allocPixels(info) || !surface.readPixels(bitmap, 0, 0)) {
                throw new IllegalStateException("Cannot read Modern Skia surface");
            }
            return new Image(width, height, bitmap.readPixels());
        }
    }

    static void upload(Identifier id, Image image) {
        NativeImage pixels = new NativeImage(image.width, image.height, false);
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                // White outside the mask avoids dark fringes with straight-alpha linear filtering.
                pixels.setPixel(x, y, image.getRGB(x, y) | 0x00FFFFFF);
            }
        }
        Minecraft.getInstance().getTextureManager().register(id, new SmoothTexture(pixels));
    }

    /**
     * Uploads a painted (not tint-only) image, keeping its colors. See {@link #prepareColor}; this is that
     * plus {@link #registerColor}, for small images painted on the render thread.
     */
    static void uploadColor(Identifier id, Image image) {
        registerColor(id, prepareColor(image));
    }

    /**
     * Turns a painted image into texture pixels. Nearly transparent pixels take the color of the nearest
     * solid pixel in their column - below first, then above - so straight-alpha linear filtering blends a
     * silhouette's edge into its own color instead of into black. Touches no game state, so it can run off
     * the render thread; the result still has to be registered on it.
     */
    static NativeImage prepareColor(Image image) {
        int w = image.width, h = image.height;
        byte[] px = image.rgba;
        // Row by row (cache-friendly), carrying each column's last solid color: bottom-up, then top-down.
        int[] carry = new int[w];
        java.util.Arrays.fill(carry, -1);
        for (int y = h - 1; y >= 0; y--) for (int x = 0; x < w; x++) carry[x] = bleed(px, (y * w + x) * 4, carry[x]);
        java.util.Arrays.fill(carry, -1);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) carry[x] = bleed(px, (y * w + x) * 4, carry[x]);
        // Skia's RGBA_8888 bytes are already NativeImage's RGBA layout: copy them in one go.
        NativeImage pixels = new NativeImage(w, h, false);
        org.lwjgl.system.MemoryUtil.memByteBuffer(pixels.getPointer(), px.length).put(px);
        return pixels;
    }

    static void registerColor(Identifier id, NativeImage pixels) {
        Minecraft.getInstance().getTextureManager().register(id, new SmoothTexture(pixels));
    }

    private static int bleed(byte[] px, int i, int carry) {
        int alpha = px[i + 3] & 255;
        if (alpha >= 128) return (px[i] & 255) << 16 | (px[i + 1] & 255) << 8 | (px[i + 2] & 255);
        if (carry >= 0 && alpha < 8) {
            px[i] = (byte)(carry >> 16);
            px[i + 1] = (byte)(carry >> 8);
            px[i + 2] = (byte)carry;
        }
        return carry;
    }

    static void release(Identifier id) {
        Minecraft.getInstance().getTextureManager().release(id);
    }

    record Image(int width, int height, byte[] rgba) {
        int getWidth() { return width; }
        int getHeight() { return height; }
        int getRGB(int x, int y) {
            int offset = (y * width + x) * 4;
            return (rgba[offset + 3] & 255) << 24 | (rgba[offset] & 255) << 16
                | (rgba[offset + 1] & 255) << 8 | (rgba[offset + 2] & 255);
        }
    }

    private static final class SmoothTexture extends DynamicTexture {
        SmoothTexture(NativeImage pixels) {
            super(() -> "SigmaModern Skia coverage", pixels);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }
}
