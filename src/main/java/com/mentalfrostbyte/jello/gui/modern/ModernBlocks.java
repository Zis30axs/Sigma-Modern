package com.mentalfrostbyte.jello.gui.modern;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.FilterMipmap;
import io.github.humbleui.skija.FilterMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.MipmapMode;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Small isometric block icons, painted by Skia from the game's own block textures (so a resource pack's
 * cobblestone shows up too) with flat per-face shading, like the inventory's block items. Painted into
 * color textures at roughly the device size they are drawn at - bucketed, as {@link ModernIcons} does -
 * and drawn with a tint, so they fade with {@link ModernStyle#alphaScope} like every other Modern primitive.
 */
final class ModernBlocks {
    enum Block {
        GRASS("grass_block_top", "grass_block_side", "grass_block_side", 0xFF91BD59, 0),
        DIRT_PATH("dirt_path_top", "dirt_path_side", "dirt_path_side", 0, 1),
        COBBLESTONE("cobblestone", "cobblestone", "cobblestone", 0, 0),
        BEDROCK("bedrock", "bedrock", "bedrock", 0, 0),
        OBSERVER("observer_top", "observer_front", "observer_side", 0, 0);

        final String top, left, right;
        /** Biome-style tint multiplied into the (grayscale) top texture; 0 for none. */
        final int topTint;
        /** Texels the top face sits below a full block (dirt path is 15/16 tall). */
        final int topInset;

        Block(String top, String left, String right, int topTint, int topInset) {
            this.top = top;
            this.left = left;
            this.right = right;
            this.topTint = topTint;
            this.topInset = topInset;
        }
    }

    private static final int[] BUCKETS = {16, 24, 32, 48, 64, 96, 128, 192, 256};
    // Flat light like the inventory's block items: the top is lit, the left face a little darker, the right more.
    private static final int SHADE_LEFT = 0xFFD0D0D0, SHADE_RIGHT = 0xFFA4A4A4;
    private static final Map<Key, Identifier> CACHE = new HashMap<>();
    private record Key(Block block, int px) {}

    private ModernBlocks() {}

    /** Draws {@code block} filling a {@code size}x{@code size} box at ({@code x}, {@code y}), multiplied by {@code color}. */
    static void draw(GuiGraphicsExtractor g, Block block, float x, float y, float size, int color) {
        color = ModernStyle.a(color);
        if ((color >>> 24) == 0 || size <= 0) return;
        float transform = Math.max((float)Math.hypot(g.pose().m00(), g.pose().m01()), (float)Math.hypot(g.pose().m10(), g.pose().m11()));
        int px = bucket(size * transform * Minecraft.getInstance().getWindow().getGuiScale());
        Identifier id = CACHE.computeIfAbsent(new Key(block, px), key -> {
            Identifier texture = Identifier.withDefaultNamespace("sigma/modern_block/" + key.block().name().toLowerCase() + "_" + key.px());
            ModernSkiaRaster.uploadColor(texture, ModernSkiaRaster.render(key.px(), key.px(), canvas -> paint(canvas, key.block(), key.px())));
            return texture;
        });
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, y);
            g.pose().scale(size / 64F, size / 64F);
            g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, 64, 64, px, px, px, px, color);
        } finally {
            g.pose().popMatrix();
        }
    }

    static void draw(GuiGraphicsExtractor g, Block block, float x, float y, float size) {
        draw(g, block, x, y, size, 0xFFFFFFFF);
    }

    private static int bucket(float px) {
        for (int b : BUCKETS) if (b >= px) return b;
        return BUCKETS[BUCKETS.length - 1];
    }

    /**
     * An isometric cube of edge {@code e}: the top face is a diamond, the two visible sides are vertical
     * parallelograms, and each face is its 16x16 texture mapped through an affine matrix.
     */
    private static void paint(Canvas c, Block block, int px) {
        float e = px * 0.47F;
        float a = e * 0.8660254F;
        float cx = px / 2F, cy = px / 2F;
        float ltX = cx - a, ltY = cy - e / 2F, tX = cx, tY = cy - e, rtX = cx + a, rtY = cy - e / 2F;
        float texel = e / 16F;
        // Pixel art stays crisp when a texel covers several pixels; below that, mipmapped linear sampling
        // keeps the skewed faces from shimmering into moiré.
        SamplingMode sampling = texel >= 2.5F ? new FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)
            : new FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR);

        Image top = load(block.top), left = load(block.left), right = load(block.right);
        try {
            if (top == null || left == null || right == null) {
                try (Paint fallback = new Paint().setColor(0xFF8A8F94).setAntiAlias(true)) {
                    c.drawRRect(RRect.makeXYWH(px * 0.15F, px * 0.15F, px * 0.7F, px * 0.7F, px * 0.12F), fallback);
                }
                return;
            }
            // Left face: from the top-left corner towards the front edge, straight down.
            face(c, left, ltX, ltY, (cx - ltX) / 16F, (cy - ltY) / 16F, 0, texel, 0xFFFFFFFF, SHADE_LEFT, sampling);
            face(c, right, cx, cy, (rtX - cx) / 16F, (rtY - cy) / 16F, 0, texel, 0xFFFFFFFF, SHADE_RIGHT, sampling);
            float drop = block.topInset * texel;
            face(c, top, ltX, ltY + drop, (tX - ltX) / 16F, (tY - ltY) / 16F, (cx - ltX) / 16F, (cy - ltY) / 16F,
                block.topTint == 0 ? 0xFFFFFFFF : block.topTint, 0xFFFFFFFF, sampling);
        } finally {
            if (top != null) top.close();
            if (left != null && left != top) left.close();
            if (right != null && right != top && right != left) right.close();
        }
    }

    /** Draws a texture so texel (u, v) lands at origin + u*U + v*V, multiplied by {@code tint} and {@code shade}. */
    private static void face(Canvas c, Image texture, float ox, float oy, float ux, float uy, float vx, float vy,
                             int tint, int shade, SamplingMode sampling) {
        c.save();
        c.concat(new Matrix33(ux, vx, ox, uy, vy, oy, 0F, 0F, 1F));
        int color = multiply(tint, shade);
        try (Paint paint = new Paint().setAntiAlias(true)) {
            if (color != 0xFFFFFFFF) paint.setColorFilter(ColorFilter.makeBlend(color, BlendMode.MODULATE));
            // A hair of overlap so neighbouring faces meet without an antialiased seam between them.
            c.drawImageRect(texture, Rect.makeXYWH(0, 0, 16, 16), Rect.makeLTRB(-0.06F, -0.06F, 16.06F, 16.06F), sampling, paint, true);
        }
        c.restore();
    }

    private static int multiply(int a, int b) {
        int r = (a >> 16 & 0xFF) * (b >> 16 & 0xFF) / 255, g = (a >> 8 & 0xFF) * (b >> 8 & 0xFF) / 255, bl = (a & 0xFF) * (b & 0xFF) / 255;
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static Image load(String name) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager()
            .getResource(Identifier.withDefaultNamespace("textures/block/" + name + ".png"));
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open()) {
            return Image.makeFromEncoded(in.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }
}
