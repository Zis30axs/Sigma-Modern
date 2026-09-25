package com.mentalfrostbyte.jello.gui.modern;

import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.RRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import java.util.HashMap;
import java.util.Map;

/** Small Skia nine-slice masks keep card corners smooth without allocating full-card textures. */
final class ModernShapeRenderer {
    private static final Map<Key, Identifier> MASKS = new HashMap<>();
    private record Key(int radius, int density) {}

    static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        color = ModernStyle.a(color);
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        int r = Math.clamp(radius, 0, Math.min(32, Math.min(w, h) / 2));
        if (r == 0) { g.fill(x, y, x + w, y + h, color); return; }
        int density = Math.clamp(Minecraft.getInstance().getWindow().getGuiScale() * 2, 2, 16);
        Key key = new Key(r, density);
        Identifier id = MASKS.computeIfAbsent(key, unused -> {
            Identifier texture = Identifier.withDefaultNamespace("sigma/modern_shape/" + r + "_" + density);
            ModernSkiaRaster.upload(texture, mask(r, density));
            return texture;
        });
        int size = 2 * r + 4;
        int[] source = {0, r + 1, r + 3, size};
        int[] xs = {x - 1, x + r, x + w - r, x + w + 1};
        int[] ys = {y - 1, y + r, y + h - r, y + h + 1};
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            int dw = xs[col + 1] - xs[col], dh = ys[row + 1] - ys[row];
            if (dw <= 0 || dh <= 0) continue;
            g.blit(RenderPipelines.GUI_TEXTURED, id, xs[col], ys[row], source[col] * density, source[row] * density,
                dw, dh, (source[col + 1] - source[col]) * density, (source[row + 1] - source[row]) * density,
                size * density, size * density, color);
        }
    }

    static ModernSkiaRaster.Image mask(int radius, int density) {
        int size = 2 * radius + 4;
        return ModernSkiaRaster.render(size * density, size * density, canvas -> {
            try (Paint paint = new Paint().setColor(0xFFFFFFFF).setAntiAlias(true)) {
                canvas.scale(density, density);
                canvas.drawRRect(RRect.makeXYWH(1, 1, size - 2, size - 2, radius), paint);
            }
        });
    }
}
