package com.mentalfrostbyte.jello.gui.modern;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Vector icons and sprites, drawn by Skia into white coverage masks and tinted at blit time.
 *
 * <p>Each icon is designed in a 64-unit box. A mask is rasterized at (roughly) the device pixel size it is
 * drawn at - bucketed so a handful of sizes cover everything - which keeps thin strokes crisp at every GUI
 * scale instead of magnifying one small bitmap.</p>
 */
final class ModernIcons {
    enum Icon {
        SOFT_DOT, CRYSTAL, SIGMA, GEAR, PERSON, MODES, POWER, MINIMIZE, MAXIMIZE, RESTORE, CLOSE, ARROW,
        BACK, PLAY, PLUS, EDIT, TRASH, REFRESH, ENTER, SEARCH, CHEVRON_UP, CHEVRON_DOWN, DUPLICATE, CHECK,
        MOUNTAIN, SERVER, WARNING, LOCK, LAN, BRANCH,
        MONITOR, SPEAKER, KEYBOARD, GLOBE, CHAT, LAYERS, ACCESSIBILITY, SHIRT, CLOUD, CHART, HEART, EYE,
        PAUSE, SKIP_PREV, SKIP_NEXT, NOTE
    }

    private static final int[] BUCKETS = {8, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96, 128, 160, 192, 256, 384, 512};
    private static final Map<Key, Identifier> CACHE = new HashMap<>();
    private record Key(Icon icon, int px) {}

    private ModernIcons() {}

    /** Draws {@code icon} filling a {@code size}×{@code size} box at ({@code x}, {@code y}), tinted {@code color}. */
    static void draw(GuiGraphicsExtractor g, Icon icon, float x, float y, float size, int color) {
        color = ModernStyle.a(color);
        if ((color >>> 24) == 0 || size <= 0) return;
        float transform = Math.max((float)Math.hypot(g.pose().m00(), g.pose().m01()),
            (float)Math.hypot(g.pose().m10(), g.pose().m11()));
        int px = bucket(size * transform * Minecraft.getInstance().getWindow().getGuiScale());
        Identifier id = CACHE.computeIfAbsent(new Key(icon, px), key -> {
            Identifier texture = Identifier.withDefaultNamespace("sigma/modern_icon/" + key.icon().name().toLowerCase() + "_" + key.px());
            ModernSkiaRaster.upload(texture, ModernSkiaRaster.render(key.px(), key.px(), canvas -> {
                canvas.scale(key.px() / 64F, key.px() / 64F);
                paint(canvas, key.icon());
            }));
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

    /** Same as {@link #draw} but centered on ({@code cx}, {@code cy}) and rotated by {@code radians}. */
    static void drawRotated(GuiGraphicsExtractor g, Icon icon, float cx, float cy, float size, float radians, int color) {
        g.pose().pushMatrix();
        try {
            g.pose().translate(cx, cy);
            g.pose().rotate(radians);
            draw(g, icon, -size / 2F, -size / 2F, size, color);
        } finally {
            g.pose().popMatrix();
        }
    }

    private static int bucket(float px) {
        for (int b : BUCKETS) if (b >= px) return b;
        return BUCKETS[BUCKETS.length - 1];
    }

    private static Paint stroke(float width) {
        return new Paint().setColor(0xFFFFFFFF).setAntiAlias(true).setMode(PaintMode.STROKE)
            .setStrokeWidth(width).setStrokeCap(PaintStrokeCap.ROUND);
    }

    private static Paint line(float width) {
        return stroke(width).setStrokeJoin(PaintStrokeJoin.ROUND);
    }

    private static void polyline(Canvas canvas, Paint paint, float... xy) {
        try (PathBuilder builder = new PathBuilder().moveTo(xy[0], xy[1])) {
            for (int i = 2; i < xy.length; i += 2) builder.lineTo(xy[i], xy[i + 1]);
            try (Path path = builder.detach()) {
                canvas.drawPath(path, paint);
            }
        }
    }

    private static Paint fill(int argb) {
        return new Paint().setColor(argb).setAntiAlias(true);
    }

    private static void polygon(Canvas canvas, Paint paint, float... xy) {
        try (PathBuilder builder = new PathBuilder().addPolygon(xy, true); Path path = builder.detach()) {
            canvas.drawPath(path, paint);
        }
    }

    private static void paint(Canvas c, Icon icon) {
        switch (icon) {
            case SOFT_DOT -> {
                try (Shader shader = Shader.makeRadialGradient(32, 32, 32,
                        new int[]{0xFFFFFFFF, 0xC0FFFFFF, 0x40FFFFFF, 0x00FFFFFF});
                     Paint p = new Paint().setAntiAlias(true).setShader(shader)) {
                    c.drawCircle(32, 32, 32, p);
                }
            }
            case CRYSTAL -> {
                try (Paint arm = stroke(4.2F); Paint twig = stroke(3.2F); Paint core = fill(0xFFFFFFFF)) {
                    for (int i = 0; i < 6; i++) {
                        c.save();
                        c.translate(32, 32);
                        c.rotate(i * 60F);
                        c.drawLine(0, 0, 0, -27, arm);
                        c.drawLine(0, -12, -7, -19, twig);
                        c.drawLine(0, -12, 7, -19, twig);
                        c.drawLine(0, -20, -4.5F, -24.5F, twig);
                        c.drawLine(0, -20, 4.5F, -24.5F, twig);
                        c.restore();
                    }
                    c.drawCircle(32, 32, 4.2F, core);
                }
            }
            case SIGMA -> paintSigma(c);
            case GEAR -> {
                try (Paint ring = stroke(7F); Paint tooth = fill(0xFFFFFFFF)) {
                    c.drawCircle(32, 32, 14F, ring);
                    for (int i = 0; i < 8; i++) {
                        c.save();
                        c.translate(32, 32);
                        c.rotate(i * 45F);
                        c.drawRRect(RRect.makeXYWH(-4.5F, -27F, 9F, 11F, 2F), tooth);
                        c.restore();
                    }
                }
            }
            case PERSON -> {
                try (Paint p = fill(0xFFFFFFFF)) {
                    c.drawCircle(32, 20, 10.5F, p);
                    try (PathBuilder b = new PathBuilder().moveTo(11, 56).cubicTo(11, 36, 53, 36, 53, 56).closePath();
                         Path path = b.detach()) {
                        c.drawPath(path, p);
                    }
                }
            }
            case MODES -> {
                try (Paint solid = fill(0xFFFFFFFF); Paint soft = fill(0x8CFFFFFF)) {
                    c.drawRRect(RRect.makeXYWH(10, 10, 19, 19, 5), solid);
                    c.drawRRect(RRect.makeXYWH(35, 10, 19, 19, 5), soft);
                    c.drawRRect(RRect.makeXYWH(10, 35, 19, 19, 5), soft);
                    c.drawRRect(RRect.makeXYWH(35, 35, 19, 19, 5), solid);
                }
            }
            case POWER -> {
                try (Paint p = stroke(5.5F)) {
                    c.drawArc(13, 15, 51, 53, -50F, 280F, false, p);
                    c.drawLine(32, 9, 32, 31, p);
                }
            }
            case MINIMIZE -> {
                try (Paint p = stroke(4.4F)) {
                    c.drawLine(17, 33, 47, 33, p);
                }
            }
            case MAXIMIZE -> {
                try (Paint p = stroke(4.4F)) {
                    c.drawRRect(RRect.makeXYWH(18, 18, 28, 28, 3), p);
                }
            }
            case RESTORE -> {
                try (Paint p = stroke(4.4F)) {
                    c.drawRRect(RRect.makeXYWH(15, 24, 25, 25, 3), p);
                    try (PathBuilder b = new PathBuilder().moveTo(24, 18).lineTo(24, 15).lineTo(49, 15).lineTo(49, 40).lineTo(46, 40);
                         Path path = b.detach()) {
                        c.drawPath(path, p);
                    }
                }
            }
            case CLOSE -> {
                try (Paint p = stroke(4.4F)) {
                    c.drawLine(19, 19, 45, 45, p);
                    c.drawLine(45, 19, 19, 45, p);
                }
            }
            case ARROW -> {
                try (Paint p = stroke(4.6F)) {
                    c.drawLine(12, 32, 50, 32, p);
                    c.drawLine(36, 18, 50, 32, p);
                    c.drawLine(36, 46, 50, 32, p);
                }
            }
            default -> paintMore(c, icon);
        }
    }

    /** The screen-level icons: list actions, status glyphs and the settings categories. */
    private static void paintMore(Canvas c, Icon icon) {
        switch (icon) {
            case BACK -> {
                try (Paint p = line(4.6F)) {
                    c.drawLine(14, 32, 52, 32, p);
                    polyline(c, p, 28, 18, 14, 32, 28, 46);
                }
            }
            case PLAY -> {
                try (Paint f = fill(0xFFFFFFFF); Paint p = line(6F)) {
                    polygon(c, f, 22, 14, 50, 32, 22, 50);
                    polygon(c, p, 22, 14, 50, 32, 22, 50);
                }
            }
            case PLUS -> {
                try (Paint p = stroke(5F)) {
                    c.drawLine(32, 13, 32, 51, p);
                    c.drawLine(13, 32, 51, 32, p);
                }
            }
            case EDIT -> {
                try (Paint p = line(4.4F)) {
                    polygon(c, p, 41, 11, 53, 23, 25, 51, 12, 52, 13, 39);
                    c.drawLine(35, 17, 47, 29, p);
                }
            }
            case TRASH -> {
                try (Paint p = line(4.4F); Paint thin = line(3.4F)) {
                    c.drawLine(12, 18, 52, 18, p);
                    polyline(c, p, 25, 17, 26, 11, 38, 11, 39, 17);
                    polyline(c, p, 18, 23, 21, 53, 43, 53, 46, 23);
                    c.drawLine(28, 29, 28, 46, thin);
                    c.drawLine(36, 29, 36, 46, thin);
                }
            }
            case REFRESH -> {
                try (Paint p = stroke(4.6F); Paint f = fill(0xFFFFFFFF)) {
                    c.drawArc(14, 14, 50, 50, 40F, 290F, false, p);
                    polygon(c, f, 55.5F, 21.5F, 43.5F, 24.5F, 52F, 34.5F);
                }
            }
            case ENTER -> {
                try (Paint p = line(4.6F)) {
                    c.drawLine(8, 32, 38, 32, p);
                    polyline(c, p, 27, 21, 38, 32, 27, 43);
                    polyline(c, p, 36, 12, 52, 12, 52, 52, 36, 52);
                }
            }
            case SEARCH -> {
                try (Paint p = stroke(4.8F)) {
                    c.drawCircle(28, 28, 15, p);
                    c.drawLine(39, 39, 52, 52, p);
                }
            }
            case CHEVRON_UP -> {
                try (Paint p = line(5F)) {
                    polyline(c, p, 16, 40, 32, 24, 48, 40);
                }
            }
            case CHEVRON_DOWN -> {
                try (Paint p = line(5F)) {
                    polyline(c, p, 16, 24, 32, 40, 48, 24);
                }
            }
            case DUPLICATE -> {
                try (Paint back = line(4F).setAlpha(150); Paint front = line(4.4F)) {
                    c.drawRRect(RRect.makeXYWH(10, 10, 30, 30, 6), back);
                    c.drawRRect(RRect.makeXYWH(24, 24, 30, 30, 6), front);
                }
            }
            case CHECK -> {
                try (Paint p = line(5.4F)) {
                    polyline(c, p, 13, 33, 27, 47, 51, 19);
                }
            }
            case MOUNTAIN -> {
                try (Paint far = fill(0x7AFFFFFF); Paint near = fill(0xB8FFFFFF); Paint snow = fill(0xFFFFFFFF)) {
                    polygon(c, far, 28, 52, 45, 24, 62, 52);
                    polygon(c, near, 2, 52, 25, 14, 48, 52);
                    polygon(c, snow, 18, 25.5F, 25, 14, 32, 25.5F, 28.5F, 23.5F, 25, 28, 21.5F, 23.5F);
                    polygon(c, snow, 40.5F, 31.5F, 45, 24, 49.5F, 31.5F, 45, 30);
                }
            }
            case SERVER -> {
                try (Paint p = line(4F); Paint f = fill(0xFFFFFFFF)) {
                    c.drawRRect(RRect.makeXYWH(10, 12, 44, 17, 5), p);
                    c.drawRRect(RRect.makeXYWH(10, 35, 44, 17, 5), p);
                    c.drawCircle(19, 20.5F, 2.8F, f);
                    c.drawCircle(19, 43.5F, 2.8F, f);
                    c.drawLine(30, 20.5F, 45, 20.5F, p);
                    c.drawLine(30, 43.5F, 45, 43.5F, p);
                }
            }
            case WARNING -> {
                try (Paint p = line(4.4F); Paint f = fill(0xFFFFFFFF)) {
                    polygon(c, p, 32, 9, 57, 53, 7, 53);
                    c.drawLine(32, 24, 32, 38, p);
                    c.drawCircle(32, 45.5F, 3F, f);
                }
            }
            case LOCK -> {
                try (Paint p = stroke(4.6F); Paint f = fill(0xFFFFFFFF)) {
                    c.drawRRect(RRect.makeXYWH(13, 28, 38, 26, 5), f);
                    c.drawArc(21, 10, 43, 34, 180F, 180F, false, p);
                    c.drawLine(21, 21, 21, 29, p);
                    c.drawLine(43, 21, 43, 29, p);
                }
            }
            case LAN -> {
                try (Paint p = stroke(4.6F); Paint f = fill(0xFFFFFFFF)) {
                    c.drawArc(6, 16, 58, 68, -135F, 90F, false, p);
                    c.drawArc(16, 26, 48, 58, -135F, 90F, false, p);
                    c.drawCircle(32, 48, 4.2F, f);
                }
            }
            case BRANCH -> {
                try (Paint p = stroke(4F);
                     PathBuilder b = new PathBuilder().moveTo(45, 28).cubicTo(45, 40, 22, 34, 20, 44);
                     Path path = b.detach()) {
                    c.drawCircle(19, 14, 6, p);
                    c.drawCircle(19, 50, 6, p);
                    c.drawCircle(45, 22, 6, p);
                    c.drawLine(19, 20, 19, 44, p);
                    c.drawPath(path, p);
                }
            }
            case MONITOR -> {
                try (Paint p = line(4.4F)) {
                    c.drawRRect(RRect.makeXYWH(8, 11, 48, 32, 5), p);
                    c.drawLine(32, 44, 32, 52, p);
                    c.drawLine(21, 53, 43, 53, p);
                }
            }
            case SPEAKER -> {
                try (Paint f = fill(0xFFFFFFFF); Paint p = stroke(4.2F)) {
                    polygon(c, f, 8, 24, 19, 24, 32, 12, 32, 52, 19, 40, 8, 40);
                    c.drawArc(24, 20, 46, 44, -50F, 100F, false, p);
                    c.drawArc(20, 12, 56, 52, -50F, 100F, false, p);
                }
            }
            case KEYBOARD -> {
                try (Paint p = line(4F); Paint f = fill(0xFFFFFFFF)) {
                    c.drawRRect(RRect.makeXYWH(5, 15, 54, 34, 6), p);
                    for (int row = 0; row < 2; row++) {
                        for (int col = 0; col < 5; col++) {
                            c.drawRRect(RRect.makeXYWH(12 + col * 9, 21 + row * 8, 5, 5, 1.2F), f);
                        }
                    }
                    c.drawLine(20, 41, 44, 41, p);
                }
            }
            case GLOBE -> {
                try (Paint p = stroke(4F); Paint thin = stroke(3.2F)) {
                    c.drawCircle(32, 32, 23, p);
                    c.drawOval(Rect.makeLTRB(22, 9, 42, 55), thin);
                    c.drawLine(9, 32, 55, 32, thin);
                    c.drawLine(13, 20, 51, 20, thin);
                    c.drawLine(13, 44, 51, 44, thin);
                }
            }
            case CHAT -> {
                try (Paint p = line(4.4F); Paint f = fill(0xFFFFFFFF)) {
                    c.drawRRect(RRect.makeXYWH(7, 10, 50, 34, 10), p);
                    polygon(c, f, 16, 42, 16, 55, 30, 42);
                    c.drawCircle(21, 27, 3.2F, f);
                    c.drawCircle(32, 27, 3.2F, f);
                    c.drawCircle(43, 27, 3.2F, f);
                }
            }
            case LAYERS -> {
                try (Paint f = fill(0xFFFFFFFF); Paint p = line(4.2F); Paint soft = line(4.2F).setAlpha(150)) {
                    polygon(c, f, 32, 8, 56, 20, 32, 32, 8, 20);
                    polyline(c, p, 8, 31, 32, 43, 56, 31);
                    polyline(c, soft, 8, 42, 32, 54, 56, 42);
                }
            }
            case ACCESSIBILITY -> {
                try (Paint p = stroke(4F); Paint ring = stroke(3.4F); Paint f = fill(0xFFFFFFFF)) {
                    c.drawCircle(32, 32, 26, ring);
                    c.drawCircle(32, 18, 4.4F, f);
                    c.drawLine(19, 26, 45, 26, p);
                    c.drawLine(32, 26, 32, 38, p);
                    c.drawLine(32, 38, 25, 50, p);
                    c.drawLine(32, 38, 39, 50, p);
                }
            }
            case SHIRT -> {
                try (Paint f = fill(0xFFFFFFFF);
                     PathBuilder b = new PathBuilder().moveTo(23, 9).quadTo(32, 19, 41, 9).lineTo(51, 13).lineTo(60, 26).lineTo(50, 33)
                         .lineTo(46, 29).lineTo(46, 55).lineTo(18, 55).lineTo(18, 29).lineTo(14, 33).lineTo(4, 26).lineTo(13, 13).closePath();
                     Path path = b.detach()) {
                    c.drawPath(path, f);
                }
            }
            case CLOUD -> {
                try (Paint f = fill(0xFFFFFFFF)) {
                    c.drawCircle(22, 37, 12, f);
                    c.drawCircle(35, 29, 15, f);
                    c.drawCircle(47, 39, 10, f);
                    c.drawRRect(RRect.makeXYWH(12, 36, 44, 13, 6.5F), f);
                }
            }
            case CHART -> {
                try (Paint f = fill(0xFFFFFFFF)) {
                    c.drawRRect(RRect.makeXYWH(10, 34, 10, 20, 3), f);
                    c.drawRRect(RRect.makeXYWH(27, 22, 10, 32, 3), f);
                    c.drawRRect(RRect.makeXYWH(44, 10, 10, 44, 3), f);
                }
            }
            case HEART -> {
                try (Paint f = fill(0xFFFFFFFF);
                     PathBuilder b = new PathBuilder().moveTo(32, 54).cubicTo(2, 34, 10, 4, 32, 18).cubicTo(54, 4, 62, 34, 32, 54).closePath();
                     Path path = b.detach()) {
                    c.drawPath(path, f);
                }
            }
            case EYE -> {
                try (Paint p = line(4.2F); Paint f = fill(0xFFFFFFFF);
                     PathBuilder b = new PathBuilder().moveTo(5, 32).quadTo(32, 6, 59, 32).quadTo(32, 58, 5, 32).closePath();
                     Path path = b.detach()) {
                    c.drawPath(path, p);
                    c.drawCircle(32, 32, 8.5F, f);
                }
            }
            case PAUSE -> {
                try (Paint f = fill(0xFFFFFFFF)) {
                    c.drawRRect(RRect.makeXYWH(17, 13, 10, 38, 3), f);
                    c.drawRRect(RRect.makeXYWH(37, 13, 10, 38, 3), f);
                }
            }
            case SKIP_PREV -> {
                try (Paint f = fill(0xFFFFFFFF); Paint p = line(5F)) {
                    polygon(c, f, 50, 15, 50, 49, 22, 32);
                    polygon(c, p, 50, 15, 50, 49, 22, 32);
                    c.drawRRect(RRect.makeXYWH(12, 14, 6, 36, 2.5F), f);
                }
            }
            case SKIP_NEXT -> {
                try (Paint f = fill(0xFFFFFFFF); Paint p = line(5F)) {
                    polygon(c, f, 14, 15, 14, 49, 42, 32);
                    polygon(c, p, 14, 15, 14, 49, 42, 32);
                    c.drawRRect(RRect.makeXYWH(46, 14, 6, 36, 2.5F), f);
                }
            }
            case NOTE -> {
                try (Paint f = fill(0xFFFFFFFF); Paint p = stroke(5F)) {
                    c.drawOval(Rect.makeXYWH(10, 40, 18, 14), f);
                    c.drawOval(Rect.makeXYWH(36, 34, 18, 14), f);
                    c.drawLine(26, 46, 26, 14, p);
                    c.drawLine(52, 40, 52, 8, p);
                    c.drawLine(26, 14, 52, 8, p);
                }
            }
            default -> {
            }
        }
    }

    /**
     * A faceted "ice crystal" Σ: the letter's outline split into triangles at different opacities, so tinted
     * over a dark sky it reads as translucent, light-catching ice rather than a flat glyph. Designed on a
     * 100×120 grid and fitted into the 64-unit box.
     */
    private static void paintSigma(Canvas c) {
        c.save();
        c.translate(5.33F, 0F);
        c.scale(64F / 120F, 64F / 120F);
        try (Paint topBar = fill(0xF0FFFFFF); Paint litA = fill(0xFFFFFFFF); Paint shadeB = fill(0x9AFFFFFF);
             Paint shadeC = fill(0x80FFFFFF); Paint litD = fill(0xD8FFFFFF); Paint bottomBar = fill(0xB8FFFFFF);
             Paint edge = new Paint().setColor(0xFFFFFFFF).setAntiAlias(true).setMode(PaintMode.STROKE).setStrokeWidth(2.2F)) {
            polygon(c, topBar, 0, 0, 100, 0, 100, 22, 30, 22, 5.2F, 22, 0, 16);
            polygon(c, litA, 5.2F, 22, 30, 22, 62, 60);
            polygon(c, shadeB, 5.2F, 22, 62, 60, 38, 60);
            polygon(c, shadeC, 38, 60, 62, 60, 30, 98);
            polygon(c, litD, 38, 60, 30, 98, 5.2F, 98);
            polygon(c, bottomBar, 5.2F, 98, 30, 98, 100, 98, 100, 120, 0, 120, 0, 104);
            polygon(c, edge, 0, 0, 100, 0, 100, 22, 30, 22, 62, 60, 30, 98, 100, 98, 100, 120, 0, 120, 0, 104, 38, 60, 0, 16);
        }
        c.restore();
    }
}
