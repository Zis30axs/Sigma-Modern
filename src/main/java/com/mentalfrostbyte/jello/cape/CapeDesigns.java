package com.mentalfrostbyte.jello.cape;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;
import java.util.List;
import java.util.Random;

/**
 * Sigma's own capes, painted with Skia instead of shipped as images - every one is original artwork, none of
 * them is a copy of a Mojang or OptiFine cape.
 *
 * <p>A design only paints one face, in that face's own coordinates ({@code w} wide, {@code h} tall, top at the
 * shoulders as seen from behind). {@link #compose} lays it out the way the vanilla cape model reads a texture:
 * the outer face at (1, 1), a plain lining at (12, 1), and the design again on both big faces of the elytra
 * at (24, 2) and (36, 2), with the trim colour on every thin edge in between.</p>
 */
final class CapeDesigns {

    /** Texture pixels per cape pixel: the textures are 64 * SCALE by 32 * SCALE. */
    static final int SCALE = 6;

    interface Painter {
        void paint(Canvas c, float w, float h);
    }

    record Design(String id, String name, int trim, int liningTop, int liningBottom, Painter painter) {}

    static final List<Design> ALL = List.of(
        new Design("sigma", "Sigma", 0xFF0A1A33, 0xFF13284A, 0xFF08101F, CapeDesigns::sigma),
        new Design("jello", "Jello", 0xFF9CD9F2, 0xFFE8F7FF, 0xFFBFE6F7, CapeDesigns::jello),
        new Design("aurora", "Aurora", 0xFF06182B, 0xFF0B2236, 0xFF06131F, CapeDesigns::aurora),
        new Design("galaxy", "Galaxy", 0xFF140A2A, 0xFF1E0F3A, 0xFF0A0618, CapeDesigns::galaxy),
        new Design("sakura", "Sakura", 0xFFE89AB5, 0xFFFFE9F0, 0xFFF7C9D8, CapeDesigns::sakura),
        new Design("inferno", "Inferno", 0xFF2A0705, 0xFF3A0A06, 0xFF1A0503, CapeDesigns::inferno),
        new Design("ocean", "Ocean", 0xFF0A4A72, 0xFF0F5C86, 0xFF07304F, CapeDesigns::ocean),
        new Design("carbon", "Carbon", 0xFF101214, 0xFF1C1F23, 0xFF0E1012, CapeDesigns::carbon),
        new Design("spectrum", "Spectrum", 0xFF2A2A3A, 0xFF34344A, 0xFF1E1E2A, CapeDesigns::spectrum),
        new Design("royal", "Royal", 0xFFC9A03E, 0xFF3A1060, 0xFF220838, CapeDesigns::royal),
        new Design("frost", "Frost", 0xFF6FB6DD, 0xFFEAF8FF, 0xFFB7E1F5, CapeDesigns::frost),
        new Design("emerald", "Emerald", 0xFF0A4A31, 0xFF0E5C3D, 0xFF083A27, CapeDesigns::emerald),
        new Design("sunset", "Sunset", 0xFF1A0833, 0xFF2B1055, 0xFF14062A, CapeDesigns::sunset)
    );

    private CapeDesigns() {
    }

    /** Paints {@code design} over a whole {@code 64 * s} by {@code 32 * s} cape texture. */
    static void compose(final Canvas c, final Design design, final int s) {
        c.clear(design.trim());
        face(c, design.painter(), 1, 1, 10, 16, s);
        face(c, CapeDesigns.lining(design), 12, 1, 10, 16, s);
        face(c, design.painter(), 24, 2, 10, 20, s);
        face(c, design.painter(), 36, 2, 10, 20, s);
    }

    private static void face(final Canvas c, final Painter painter, final int x, final int y, final int w, final int h, final int s) {
        c.save();
        c.translate(x * s, y * s);
        c.clipRect(Rect.makeWH(w * s, h * s));
        painter.paint(c, w * s, h * s);
        c.restore();
    }

    private static Painter lining(final Design design) {
        return (c, w, h) -> {
            vertical(c, w, h, design.liningTop(), design.liningBottom());
            try (Paint edge = stroke(design.trim(), w * 0.05F)) {
                c.drawRect(Rect.makeXYWH(w * 0.04F, w * 0.04F, w * 0.92F, h - w * 0.08F), edge);
            }
        };
    }

    // --- designs ----------------------------------------------------------------------------------

    private static void sigma(final Canvas c, final float w, final float h) {
        vertical(c, w, h, 0xFF13305A, 0xFF0A1730, 0xFF060D1C);
        glow(c, w / 2F, h * 0.45F, w * 0.45F, 0x6636C8FF, w * 0.16F);
        float s = w * 0.74F, ox = (w - s) / 2F, oy = h * 0.45F - s / 2F;
        float[] unit = {
            0.18F, 0.12F, 0.84F, 0.12F, 0.84F, 0.27F, 0.43F, 0.27F, 0.64F, 0.50F, 0.43F, 0.73F,
            0.84F, 0.73F, 0.84F, 0.88F, 0.18F, 0.88F, 0.18F, 0.77F, 0.46F, 0.50F, 0.18F, 0.23F
        };
        float[] xy = new float[unit.length];
        for (int i = 0; i < unit.length; i += 2) {
            xy[i] = ox + unit[i] * s;
            xy[i + 1] = oy + unit[i + 1] * s;
        }
        try (MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, w * 0.05F);
             Paint glow = fill(0xCC4FD8FF).setMaskFilter(blur)) {
            polygon(c, xy, glow);
        }
        try (Shader shader = Shader.makeLinearGradient(0, oy, 0, oy + s, new int[]{0xFFF2FCFF, 0xFF8FE3FF, 0xFF3AA8E8});
             Paint body = fill(0xFFFFFFFF).setShader(shader)) {
            polygon(c, xy, body);
        }
        try (Paint frame = stroke(0x664FD8FF, w * 0.025F)) {
            c.drawRect(Rect.makeXYWH(w * 0.07F, w * 0.07F, w * 0.86F, h - w * 0.14F), frame);
        }
        try (Paint line = stroke(0xAA8FE3FF, w * 0.03F)) {
            c.drawLine(w * 0.3F, h * 0.86F, w * 0.7F, h * 0.86F, line);
        }
    }

    private static void jello(final Canvas c, final float w, final float h) {
        vertical(c, w, h, 0xFFF6FCFF, 0xFFD9F2FC, 0xFFB5E3F6);
        Random random = new Random(7);
        float u = w / 10F;
        for (int i = 0; i < 16; i++) {
            float x = random.nextFloat() * w, y = random.nextFloat() * h, r = u * (0.4F + random.nextFloat() * 1.3F);
            circle(c, x, y, r, 0x2230B8F0);
            try (Paint ring = stroke(0x7730A8D8, u * 0.12F)) {
                c.drawCircle(x, y, r, ring);
            }
            circle(c, x - r * 0.35F, y - r * 0.35F, r * 0.22F, 0xCCFFFFFF);
        }
        glow(c, w * 0.5F, h * 0.08F, w * 0.6F, 0x55FFFFFF, w * 0.12F);
    }

    private static void aurora(final Canvas c, final float w, final float h) {
        vertical(c, w, h, 0xFF050F22, 0xFF0A2236, 0xFF0D3638);
        stars(c, w, h, 3L, 45, w * 0.012F, 0xFFFFFFFF);
        int[] colors = {0xBB30FFB0, 0xAA40D8FF, 0x99A070FF};
        for (int band = 0; band < 3; band++) {
            float base = h * (0.22F + band * 0.17F);
            int steps = 24;
            float[] xy = new float[(steps + 1) * 4];
            for (int i = 0; i <= steps; i++) {
                float t = i / (float) steps, x = t * w;
                float top = base + (float) Math.sin(t * Math.PI * 2.2 + band * 1.7) * h * 0.05F;
                xy[i * 2] = x;
                xy[i * 2 + 1] = top;
                int j = (steps + 1) * 2 + (steps - i) * 2;
                xy[j] = x;
                xy[j + 1] = top + h * 0.16F;
            }
            int color = colors[band];
            try (Shader shader = Shader.makeLinearGradient(0, base - h * 0.06F, 0, base + h * 0.2F, new int[]{color, color & 0x00FFFFFF});
                 MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, w * 0.035F);
                 Paint paint = fill(0xFFFFFFFF).setShader(shader).setMaskFilter(blur)) {
                polygon(c, xy, paint);
            }
        }
    }

    private static void galaxy(final Canvas c, final float w, final float h) {
        try (Shader shader = Shader.makeRadialGradient(w * 0.5F, h * 0.45F, h * 0.7F, new int[]{0xFF3A1460, 0xFF160A30, 0xFF05030E});
             Paint paint = new Paint().setShader(shader)) {
            c.drawRect(Rect.makeWH(w, h), paint);
        }
        glow(c, w * 0.3F, h * 0.32F, w * 0.32F, 0x77FF4FD8, w * 0.16F);
        glow(c, w * 0.72F, h * 0.58F, w * 0.34F, 0x774F7DFF, w * 0.16F);
        glow(c, w * 0.42F, h * 0.78F, w * 0.26F, 0x5536E0FF, w * 0.14F);
        c.save();
        c.translate(w / 2F, h / 2F);
        c.rotate(-35F);
        try (MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, w * 0.08F);
             Paint band = fill(0x40FFE6FF).setMaskFilter(blur)) {
            c.drawOval(Rect.makeXYWH(-h * 0.6F, -w * 0.09F, h * 1.2F, w * 0.18F), band);
        }
        c.restore();
        stars(c, w, h, 11L, 90, w * 0.012F, 0xFFFFFFFF);
        Random random = new Random(5);
        for (int i = 0; i < 5; i++) {
            float x = random.nextFloat() * w, y = random.nextFloat() * h;
            glow(c, x, y, w * 0.03F, 0xCCFFFFFF, w * 0.02F);
            circle(c, x, y, w * 0.012F, 0xFFFFFFFF);
        }
    }

    private static void sakura(final Canvas c, final float w, final float h) {
        vertical(c, w, h, 0xFFFFF1F5, 0xFFFFD6E3, 0xFFF9BCD0);
        float u = w / 10F;
        try (Paint branch = stroke(0xFF5A3A3E, u * 0.55F);
             PathBuilder builder = new PathBuilder()
                 .moveTo(-u, h * 0.30F)
                 .cubicTo(w * 0.3F, h * 0.24F, w * 0.55F, h * 0.12F, w + u, h * 0.05F)
                 .moveTo(w * 0.45F, h * 0.17F)
                 .cubicTo(w * 0.55F, h * 0.24F, w * 0.62F, h * 0.30F, w * 0.78F, h * 0.33F);
             Path path = builder.detach()) {
            c.drawPath(path, branch);
        }
        float[][] flowers = {{0.12F, 0.27F, 1.2F}, {0.36F, 0.2F, 1.0F}, {0.62F, 0.12F, 1.3F}, {0.86F, 0.07F, 0.9F},
            {0.58F, 0.26F, 0.9F}, {0.8F, 0.33F, 1.1F}, {0.25F, 0.33F, 0.8F}};
        for (float[] f : flowers) {
            blossom(c, f[0] * w, f[1] * h, u * f[2]);
        }
        Random random = new Random(21);
        for (int i = 0; i < 18; i++) {
            float x = random.nextFloat() * w, y = h * (0.38F + random.nextFloat() * 0.6F), r = u * (0.25F + random.nextFloat() * 0.25F);
            c.save();
            c.translate(x, y);
            c.rotate(random.nextFloat() * 180F);
            try (Paint petal = fill(random.nextBoolean() ? 0xEEFF9DBA : 0xEEFFC2D4)) {
                c.drawOval(Rect.makeXYWH(-r, -r * 0.55F, r * 2F, r * 1.1F), petal);
            }
            c.restore();
        }
    }

    private static void blossom(final Canvas c, final float x, final float y, final float r) {
        for (int i = 0; i < 5; i++) {
            double a = Math.PI * 2 * i / 5 - Math.PI / 2;
            circle(c, x + (float) Math.cos(a) * r * 0.55F, y + (float) Math.sin(a) * r * 0.55F, r * 0.5F, 0xFFFF9DBA);
        }
        for (int i = 0; i < 5; i++) {
            double a = Math.PI * 2 * i / 5 - Math.PI / 2;
            circle(c, x + (float) Math.cos(a) * r * 0.5F, y + (float) Math.sin(a) * r * 0.5F, r * 0.32F, 0xFFFFC7D8);
        }
        circle(c, x, y, r * 0.22F, 0xFFFFD45A);
    }

    private static void inferno(final Canvas c, final float w, final float h) {
        vertical(c, w, h, 0xFF120303, 0xFF3A0805, 0xFFA02008);
        int[][] layers = {{0xCCFF4A0C, 100}, {0xDDFF9420, 70}, {0xEEFFE070, 42}};
        Random random = new Random(9);
        float[] xs = new float[7];
        float[] hs = new float[7];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = w * (i + 0.2F + random.nextFloat() * 0.6F) / 6.5F;
            hs[i] = h * (0.45F + random.nextFloat() * 0.35F);
        }
        for (int[] layer : layers) {
            float k = layer[1] / 100F;
            try (MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, w * 0.02F);
                 Paint paint = fill(layer[0]).setMaskFilter(blur)) {
                for (int i = 0; i < xs.length; i++) {
                    flame(c, paint, xs[i], h + w * 0.02F, w * 0.13F * k + w * 0.03F, hs[i] * k);
                }
            }
        }
        for (int i = 0; i < 14; i++) {
            float x = random.nextFloat() * w, y = random.nextFloat() * h * 0.6F;
            glow(c, x, y, w * 0.025F, 0xCCFFA040, w * 0.02F);
            circle(c, x, y, w * 0.01F, 0xFFFFE6A0);
        }
    }

    private static void flame(final Canvas c, final Paint paint, final float x, final float bottom, final float halfWidth, final float height) {
        try (PathBuilder builder = new PathBuilder()
                 .moveTo(x - halfWidth, bottom)
                 .cubicTo(x - halfWidth, bottom - height * 0.5F, x + halfWidth * 0.6F, bottom - height * 0.6F, x, bottom - height)
                 .cubicTo(x - halfWidth * 0.4F, bottom - height * 0.6F, x + halfWidth, bottom - height * 0.4F, x + halfWidth, bottom)
                 .closePath();
             Path path = builder.detach()) {
            c.drawPath(path, paint);
        }
    }

    private static void ocean(final Canvas c, final float w, final float h) {
        vertical(c, w, h, 0xFF3FD0E0, 0xFF1180AE, 0xFF073062);
        try (MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, w * 0.04F);
             Paint ray = fill(0x26FFFFFF).setMaskFilter(blur)) {
            for (int i = 0; i < 4; i++) {
                float x = w * (0.1F + i * 0.27F);
                polygon(c, new float[]{x, 0, x + w * 0.1F, 0, x + w * 0.32F, h, x + w * 0.12F, h}, ray);
            }
        }
        float u = w / 10F;
        try (Paint wave = stroke(0x55FFFFFF, u * 0.22F)) {
            for (int line = 0; line < 7; line++) {
                float base = h * (0.1F + line * 0.13F);
                int steps = 20;
                float[] pts = new float[(steps + 1) * 2];
                for (int i = 0; i <= steps; i++) {
                    float t = i / (float) steps;
                    pts[i * 2] = t * w;
                    pts[i * 2 + 1] = base + (float) Math.sin(t * Math.PI * 3 + line) * u * 0.4F;
                }
                try (PathBuilder builder = new PathBuilder().moveTo(pts[0], pts[1]).polylineTo(pts); Path path = builder.detach()) {
                    c.drawPath(path, wave);
                }
            }
        }
        Random random = new Random(13);
        try (Paint bubble = stroke(0x88E6FBFF, u * 0.1F)) {
            for (int i = 0; i < 10; i++) {
                c.drawCircle(random.nextFloat() * w, h * (0.4F + random.nextFloat() * 0.55F), u * (0.15F + random.nextFloat() * 0.3F), bubble);
            }
        }
    }

    private static void carbon(final Canvas c, final float w, final float h) {
        try (Paint base = fill(0xFF141619)) {
            c.drawRect(Rect.makeWH(w, h), base);
        }
        float cell = w / 10F;
        for (int gy = 0; gy * cell < h; gy++) {
            for (int gx = 0; gx * cell < w; gx++) {
                float x = gx * cell, y = gy * cell;
                boolean across = ((gx + gy) & 1) == 0;
                try (Shader shader = across
                         ? Shader.makeLinearGradient(x, y, x, y + cell, new int[]{0xFF30343A, 0xFF16181B})
                         : Shader.makeLinearGradient(x, y, x + cell, y, new int[]{0xFF2A2D32, 0xFF121416});
                     Paint paint = new Paint().setShader(shader)) {
                    c.drawRect(Rect.makeXYWH(x + cell * 0.04F, y + cell * 0.04F, cell * 0.92F, cell * 0.92F), paint);
                }
            }
        }
        try (Paint red = fill(0xFFE0242F)) {
            c.drawRect(Rect.makeXYWH(w * 0.5F - cell * 0.9F, 0, cell * 1.8F, h), red);
            c.drawRect(Rect.makeXYWH(w * 0.5F - cell * 1.6F, 0, cell * 0.25F, h), red);
            c.drawRect(Rect.makeXYWH(w * 0.5F + cell * 1.35F, 0, cell * 0.25F, h), red);
        }
        sheen(c, w, h, 0x30FFFFFF);
    }

    private static void spectrum(final Canvas c, final float w, final float h) {
        vertical(c, w, h, 0xFFFF4F6D, 0xFFFF9F43, 0xFFFFE15A, 0xFF5AE67A, 0xFF3FC8FF, 0xFF7A6BFF, 0xFFD96BFF);
        sheen(c, w, h, 0x50FFFFFF);
        Random random = new Random(17);
        for (int i = 0; i < 7; i++) {
            sparkle(c, random.nextFloat() * w, random.nextFloat() * h, w * (0.04F + random.nextFloat() * 0.05F), 0xEEFFFFFF);
        }
    }

    private static void royal(final Canvas c, final float w, final float h) {
        vertical(c, w, h, 0xFF57198A, 0xFF3A0F63, 0xFF240840);
        float u = w / 10F;
        for (float y = u; y < h; y += u * 1.6F) {
            for (float x = u; x < w; x += u * 1.6F) {
                circle(c, x, y, u * 0.12F, 0x40F2C14E);
            }
        }
        try (Paint gold = stroke(0xFFF2C14E, u * 0.35F); Paint thin = stroke(0xCCF2C14E, u * 0.12F)) {
            c.drawRect(Rect.makeXYWH(u * 0.6F, u * 0.6F, w - u * 1.2F, h - u * 1.2F), gold);
            c.drawRect(Rect.makeXYWH(u * 1.15F, u * 1.15F, w - u * 2.3F, h - u * 2.3F), thin);
        }
        float cx = w / 2F, cy = h * 0.45F, s = w * 0.3F;
        glow(c, cx, cy, s * 1.1F, 0x55F2C14E, s * 0.5F);
        try (Shader shader = Shader.makeLinearGradient(0, cy - s, 0, cy + s, new int[]{0xFFFFE9A8, 0xFFF2C14E, 0xFFB07F22});
             Paint gold = fill(0xFFFFFFFF).setShader(shader)) {
            // A crown over a diamond.
            polygon(c, new float[]{
                cx - s, cy - s * 0.1F, cx - s, cy - s * 0.85F, cx - s * 0.5F, cy - s * 0.45F, cx, cy - s * 1.0F,
                cx + s * 0.5F, cy - s * 0.45F, cx + s, cy - s * 0.85F, cx + s, cy - s * 0.1F
            }, gold);
            polygon(c, new float[]{cx, cy + s * 0.05F, cx + s * 0.55F, cy + s * 0.6F, cx, cy + s * 1.15F, cx - s * 0.55F, cy + s * 0.6F}, gold);
        }
        circle(c, cx, cy - s * 0.35F, s * 0.13F, 0xFFE0304F);
        circle(c, cx, cy + s * 0.6F, s * 0.16F, 0xFF57198A);
    }

    private static void frost(final Canvas c, final float w, final float h) {
        vertical(c, w, h, 0xFFF0FAFF, 0xFFA9DCF4, 0xFF5AA9D6);
        glow(c, w * 0.5F, 0, w * 0.7F, 0x88FFFFFF, w * 0.15F);
        Random random = new Random(29);
        for (int i = 0; i < 9; i++) {
            float r = w * (0.07F + random.nextFloat() * 0.1F);
            snowflake(c, random.nextFloat() * w, random.nextFloat() * h, r, 0xEEFFFFFF, r * 0.12F);
        }
        snowflake(c, w * 0.5F, h * 0.45F, w * 0.28F, 0xFFFFFFFF, w * 0.03F);
    }

    private static void snowflake(final Canvas c, final float x, final float y, final float r, final int color, final float width) {
        try (Paint line = stroke(color, width)) {
            for (int i = 0; i < 6; i++) {
                double a = Math.PI * i / 3;
                float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
                c.drawLine(x, y, x + dx * r, y + dy * r, line);
                float bx = x + dx * r * 0.55F, by = y + dy * r * 0.55F;
                for (int side = -1; side <= 1; side += 2) {
                    double b = a + side * Math.PI / 4;
                    c.drawLine(bx, by, bx + (float) Math.cos(b) * r * 0.3F, by + (float) Math.sin(b) * r * 0.3F, line);
                }
            }
        }
    }

    private static void emerald(final Canvas c, final float w, final float h) {
        try (Paint base = fill(0xFF0B6A45)) {
            c.drawRect(Rect.makeWH(w, h), base);
        }
        float dw = w / 2.5F, dh = dw * 1.5F;
        for (int row = -1; row * dh / 2F < h + dh; row++) {
            for (int col = -1; col * dw < w + dw; col++) {
                float cx = col * dw + ((row & 1) == 0 ? 0 : dw / 2F), cy = row * dh / 2F;
                try (Paint paint = fill(((row + col) & 1) == 0 ? 0xFF14A86B : 0xFF0D8655)) {
                    polygon(c, new float[]{cx, cy - dh / 2F, cx + dw / 2F, cy, cx, cy + dh / 2F, cx - dw / 2F, cy}, paint);
                }
            }
        }
        try (Paint line = stroke(0x99F2D27A, w * 0.012F)) {
            for (float x = -h; x < w + h; x += dw) {
                c.drawLine(x, 0, x + h * dw / dh, h, line);
                c.drawLine(x, 0, x - h * dw / dh, h, line);
            }
        }
        sheen(c, w, h, 0x28FFFFFF);
    }

    private static void sunset(final Canvas c, final float w, final float h) {
        float horizon = h * 0.62F;
        try (Shader shader = Shader.makeLinearGradient(0, 0, 0, horizon, new int[]{0xFF2B1055, 0xFF7A1E7A, 0xFFE8456B, 0xFFFF9E5A});
             Paint paint = new Paint().setShader(shader)) {
            c.drawRect(Rect.makeWH(w, horizon), paint);
        }
        stars(c, w, horizon * 0.5F, 31L, 18, w * 0.01F, 0xCCFFFFFF);
        float cx = w / 2F, cy = horizon - w * 0.05F, r = w * 0.32F;
        glow(c, cx, cy, r * 1.2F, 0x66FFB060, r * 0.4F);
        try (Shader shader = Shader.makeLinearGradient(0, cy - r, 0, cy + r * 0.3F, new int[]{0xFFFFF07A, 0xFFFF7A6B});
             Paint sun = fill(0xFFFFFFFF).setShader(shader)) {
            float top = cy - r;
            float band = r * 0.18F;
            for (int i = 0; top < horizon; i++) {
                float gap = i < 3 ? 0F : band * 0.12F * (i - 2);
                float bottom = Math.min(horizon, top + band - gap);
                c.save();
                c.clipRect(Rect.makeLTRB(0, top, w, bottom));
                c.drawCircle(cx, cy, r, sun);
                c.restore();
                top += band;
            }
        }
        try (Paint ground = fill(0xFF1A0833)) {
            c.drawRect(Rect.makeLTRB(0, horizon, w, h), ground);
        }
        try (Paint grid = stroke(0xBBFF4FD8, w * 0.012F)) {
            float y = horizon;
            for (float step = h * 0.02F; y < h; step *= 1.35F) {
                c.drawLine(0, y, w, y, grid);
                y += step;
            }
            for (int i = -6; i <= 6; i++) {
                c.drawLine(cx + i * w * 0.04F, horizon, cx + i * w * 0.3F, h, grid);
            }
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static Paint fill(final int color) {
        return new Paint().setAntiAlias(true).setColor(color);
    }

    private static Paint stroke(final int color, final float width) {
        return fill(color).setMode(PaintMode.STROKE).setStrokeWidth(width).setStrokeCap(PaintStrokeCap.ROUND);
    }

    private static void vertical(final Canvas c, final float w, final float h, final int... colors) {
        try (Shader shader = Shader.makeLinearGradient(0, 0, 0, h, colors); Paint paint = new Paint().setShader(shader)) {
            c.drawRect(Rect.makeWH(w, h), paint);
        }
    }

    /** A soft diagonal highlight across the face, like light on fabric. */
    private static void sheen(final Canvas c, final float w, final float h, final int color) {
        try (Shader shader = Shader.makeLinearGradient(0, 0, w, h * 0.6F,
                 new int[]{color & 0x00FFFFFF, color, color & 0x00FFFFFF}, new float[]{0.25F, 0.45F, 0.65F});
             Paint paint = new Paint().setShader(shader)) {
            c.drawRect(Rect.makeWH(w, h), paint);
        }
    }

    private static void circle(final Canvas c, final float x, final float y, final float r, final int color) {
        try (Paint paint = fill(color)) {
            c.drawCircle(x, y, r, paint);
        }
    }

    private static void glow(final Canvas c, final float x, final float y, final float r, final int color, final float blur) {
        try (MaskFilter filter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, Math.max(0.5F, blur));
             Paint paint = fill(color).setMaskFilter(filter)) {
            c.drawCircle(x, y, r, paint);
        }
    }

    private static void polygon(final Canvas c, final float[] xy, final Paint paint) {
        try (PathBuilder builder = new PathBuilder().addPolygon(xy, true); Path path = builder.detach()) {
            c.drawPath(path, paint);
        }
    }

    private static void sparkle(final Canvas c, final float x, final float y, final float r, final int color) {
        float k = r * 0.22F;
        try (Paint paint = fill(color)) {
            polygon(c, new float[]{x, y - r, x + k, y - k, x + r, y, x + k, y + k, x, y + r, x - k, y + k, x - r, y, x - k, y - k}, paint);
        }
    }

    private static void stars(final Canvas c, final float w, final float h, final long seed, final int count, final float maxR, final int color) {
        Random random = new Random(seed);
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * w, y = random.nextFloat() * h;
            float r = maxR * (0.4F + random.nextFloat() * 0.8F);
            int alpha = 90 + random.nextInt(166);
            circle(c, x, y, r, (alpha << 24) | (color & 0x00FFFFFF));
        }
    }
}
