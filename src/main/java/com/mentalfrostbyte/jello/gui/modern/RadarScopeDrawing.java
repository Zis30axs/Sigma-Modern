package com.mentalfrostbyte.jello.gui.modern;

import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Shared scope ink and a bounded cache of immutable RWR faces. Animated marks use ordinary GUI geometry. */
final class RadarScopeDrawing {
    static final int AMBER = 0xFFFFD879, RED = 0xFFFF7777;
    private static final int[] SIZES = {128, 192, 256, 384, 512};
    private static final Map<Integer, Identifier> FACES = new HashMap<>();

    private RadarScopeDrawing() {}

    static int ink(boolean wt) { return wt ? 0xFF72FF84 : 0xFFAADEFF; }
    static int dim(boolean wt) { return wt ? 0xAA72FF84 : 0xB78EAEBE; }
    static int grid(boolean wt) { return wt ? 0x5572FF84 : 0x498EBFD8; }

    static float scale(float requested, int w, int h, GuiGraphicsExtractor g) {
        return Math.max(0F, Math.min(requested, Math.min((g.guiWidth() - 12F) / w, (g.guiHeight() - 12F) / h)));
    }

    static void panel(GuiGraphicsExtractor g, int w, int h, boolean wt, float background) {
        if (background <= 0F) return;
        if (wt) {
            ModernStyle.fill(g, 0, 0, w, h, Math.round(background * 150F) << 24 | 0x031108);
            line(g, 0, 0, 16, 0, 1, dim(true));
            line(g, 0, 0, 0, 10, 1, dim(true));
            line(g, w - 16, h - 1, w - 1, h - 1, 1, dim(true));
            line(g, w - 1, h - 10, w - 1, h - 1, 1, dim(true));
        } else {
            ModernStyle.dropShadow(g, 0, 2, w, h, 10, background * 0.65F);
            g.sigma$blurRounded(0, 0, w, h, 10, background * 0.8F);
            ModernStyle.rounded(g, 0, 0, w, h, 10, ModernTypography.fade(0x668BBFD9, background));
            ModernStyle.rounded(g, 1, 1, w - 2, h - 2, 9, Math.round(background * 255F) << 24 | 0x081B27);
            ModernStyle.fillGradient(g, 10, 1, w - 10, 25, 0x15C3EFFF, 0x00C3EFFF);
        }
    }

    static void text(GuiGraphicsExtractor g, boolean wt, String value, float x, float y, int color) {
        if (!wt) {
            ModernTypography.draw(g, value, x, y, 0.72F, color, false);
            return;
        }
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, y);
            g.pose().scale(0.8F, 0.8F);
            g.text(Minecraft.getInstance().font, value, 0, 0, ModernStyle.a(color), false);
        } finally {
            g.pose().popMatrix();
        }
    }

    static float width(boolean wt, String value) {
        return wt ? Minecraft.getInstance().font.width(value) * 0.8F : ModernTypography.width(value) * 0.72F;
    }

    static void centered(GuiGraphicsExtractor g, boolean wt, String value, float x, float y, int color) {
        text(g, wt, value, x - width(wt, value) / 2F, y, color);
    }

    static void right(GuiGraphicsExtractor g, boolean wt, String value, float x, float y, int color) {
        text(g, wt, value, x - width(wt, value), y, color);
    }

    static String fit(boolean wt, String value, int width) {
        return wt ? Minecraft.getInstance().font.plainSubstrByWidth(value, (int) (width / 0.8F))
                : ModernTypography.ellipsize(value, (int) (width / 0.72F));
    }

    static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float thickness, int color) {
        float length = (float) Math.hypot(x2 - x1, y2 - y1);
        if (length < 0.01F) return;
        g.pose().pushMatrix();
        try {
            g.pose().translate(x1, y1);
            g.pose().rotate((float) Math.atan2(y2 - y1, x2 - x1));
            g.pose().translate(0, -thickness * 0.5F);
            g.pose().scale(length, thickness);
            ModernStyle.fill(g, 0, 0, 1, 1, color);
        } finally {
            g.pose().popMatrix();
        }
    }

    static void bracket(GuiGraphicsExtractor g, float x, float y, int color) {
        line(g, x - 7, y - 5, x - 7, y + 5, 1, color);
        line(g, x + 7, y - 5, x + 7, y + 5, 1, color);
        line(g, x - 7, y - 5, x - 4, y - 5, 1, color);
        line(g, x + 4, y + 5, x + 7, y + 5, 1, color);
    }

    static void rwrFace(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        float transform = Math.max((float) Math.hypot(g.pose().m00(), g.pose().m01()),
                (float) Math.hypot(g.pose().m10(), g.pose().m11()));
        int pixels = 512;
        float wanted = size * transform * Minecraft.getInstance().getWindow().getGuiScale();
        for (int candidate : SIZES) if (wanted <= candidate) { pixels = candidate; break; }
        Identifier texture = FACES.computeIfAbsent(pixels, px -> {
            Identifier id = Identifier.withDefaultNamespace("sigma/rwr/face_" + px);
            ModernSkiaRaster.upload(id, faceMask(px));
            return id;
        });
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, size, size, pixels, pixels,
                pixels, pixels, ModernStyle.a(color));
    }

    static ModernSkiaRaster.Image faceMask(int pixels) {
        if (pixels < 1 || pixels > 512) throw new IllegalArgumentException("RWR face must be 1-512 pixels");
        return ModernSkiaRaster.render(pixels, pixels, canvas -> {
            canvas.scale(pixels / 128F, pixels / 128F);
            try (Paint paint = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE).setStrokeWidth(0.8F)) {
                paint.setColor(0xBBFFFFFF);
                canvas.drawCircle(64, 64, 61, paint);
                paint.setColor(0x55FFFFFF);
                canvas.drawCircle(64, 64, 34, paint);
                for (int tick = 0; tick < 24; tick++) {
                    double angle = tick * Math.TAU / 24;
                    float inner = tick % 2 == 0 ? 55 : 58;
                    paint.setColor(tick % 2 == 0 ? 0xCCFFFFFF : 0x66FFFFFF);
                    canvas.drawLine(64 + (float) Math.sin(angle) * inner, 64 - (float) Math.cos(angle) * inner,
                            64 + (float) Math.sin(angle) * 61, 64 - (float) Math.cos(angle) * 61, paint);
                }
                paint.setColor(0x66FFFFFF);
                canvas.drawLine(64, 30, 64, 43, paint);
                canvas.drawLine(64, 85, 64, 98, paint);
                canvas.drawLine(30, 64, 43, 64, paint);
                canvas.drawLine(85, 64, 98, 64, paint);
            }
        });
    }
}
