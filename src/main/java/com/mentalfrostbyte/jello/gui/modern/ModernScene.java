package com.mentalfrostbyte.jello.gui.modern;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * SigmaModern's flat-illustration winter night: a gradient sky with stars, a moon and the odd shooting star,
 * two slowly drifting aurora ribbons, two low-poly mountain ranges (lit and shadowed faces, jagged snowcaps)
 * and a snowfield with pines.
 *
 * <p>Everything that isn't animated per-star is painted by Skia into textures once per window size - never
 * per frame - each a little wider than the screen so the layers can slide apart for mouse parallax.
 * Rasters are capped at 4096×2048 and simply upscaled past that. Painting takes the better part of a second
 * at large sizes, so it runs on a background thread: until a new size's layers are ready, the previous ones
 * are drawn zoomed to cover it, then the new set crossfades in (a maximize or restore used to freeze the
 * window while Windows stretched the last frame). Shared by every SigmaModern screen that
 * shows no world, so the main menu and ClickGUI never paint the same scene twice.</p>
 */
final class ModernScene {
    private static final ModernScene SHARED = new ModernScene();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Sigma/ModernScene");

    private static final int SKY_TOP = 0xFF050F1A;
    private static final int SKY_MID = 0xFF0C2438;
    private static final int SKY_HORIZON = 0xFF356A8F;
    private static final int FAR = 0, MID = 1, NEAR = 2, AURORA_A = 3, AURORA_B = 4;
    private static final int[] MARGIN = {6, 13, 24, 90, 90};
    // Painted below each layer's nominal bottom, so vertical mouse parallax (up to MARGIN / 4 = 6px for the
    // near layer) never lifts a layer far enough to uncover the sky behind the snowfield.
    private static final int BLEED = 10;

    private static final ExecutorService PAINTER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SigmaModern scene painter");
        thread.setDaemon(true);
        return thread;
    });

    private final Layer[] layers = new Layer[5];
    private @Nullable CompletableFuture<Built> pending;
    private volatile @Nullable Key failed;
    private Layer @Nullable [] previous;
    private int previousW, previousH;
    private long installedAt;
    private boolean firstInstall;
    private final float[] starX = new float[90], starY = new float[90], starPhase = new float[90], starSize = new float[90];
    private int builtW = -1, builtH = -1, builtScale = -1;
    private long serial;

    private record Layer(Identifier id, int top, int w, int h, int texW, int texH) {}
    private record Key(int w, int h, int scale) {}
    /** A layer painted off-thread, waiting for its texture to be registered on the render thread. */
    private record Prepared(int index, String name, int top, int w, int h, int texW, int texH, NativeImage pixels) {}
    private record Built(Key key, Prepared[] layers) {}

    private ModernScene() {
        Random random = new Random(0x5E1A);
        for (int i = 0; i < this.starX.length; i++) {
            this.starX[i] = random.nextFloat();
            this.starY[i] = (float)Math.pow(random.nextFloat(), 1.6) * 0.52F;
            this.starPhase[i] = random.nextFloat() * 6.2832F;
            this.starSize[i] = 1.2F + random.nextFloat() * random.nextFloat() * 2.4F;
        }
    }

    static ModernScene shared() {
        return SHARED;
    }

    /**
     * @param parallaxX -1..1, from the mouse's horizontal position
     * @param parallaxY -1..1, from the mouse's vertical position
     * @param time      seconds, for stars, aurora drift and shooting stars
     */
    void render(GuiGraphicsExtractor g, int w, int h, float parallaxX, float parallaxY, float time) {
        ensure(w, h);
        int horizon = horizon(h);
        ModernStyle.fillGradient(g, 0, 0, w, Math.round(horizon * 0.55F), SKY_TOP, SKY_MID);
        ModernStyle.fillGradient(g, 0, Math.round(horizon * 0.55F), w, horizon + h / 12, SKY_MID, SKY_HORIZON);
        ModernStyle.fill(g, 0, horizon + h / 12, w, h, SKY_HORIZON);

        for (int i = 0; i < this.starX.length; i++) {
            float twinkle = 0.5F + 0.5F * (float)Math.sin(time * (0.8F + (i % 7) * 0.23F) + this.starPhase[i]);
            float depthFade = 1F - this.starY[i] / 0.6F;
            int alpha = Math.round(255 * (0.18F + 0.62F * twinkle * twinkle) * depthFade);
            float sx = this.starX[i] * w - parallaxX * 2F, sy = this.starY[i] * h - parallaxY;
            ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, sx, sy, this.starSize[i], alpha << 24 | 0xE8F4FF);
        }
        drawShootingStar(g, w, h, time);
        drawMoon(g, w, h, parallaxX, parallaxY);

        // A new layer set fades in over the old one (or, the first time, over the bare sky); the old one is
        // released once it has faded out.
        float fade = Math.min(1F, (System.nanoTime() - this.installedAt) / 1_000_000_000F / 0.35F);
        if (fade >= 1F && this.previous != null) {
            for (Layer layer : this.previous) if (layer != null) ModernSkiaRaster.release(layer.id());
            this.previous = null;
        }
        float currentAlpha = this.previous == null && this.firstInstall ? fade : 1F;

        float drift = (float)Math.sin(time * 0.045F) * 70F;
        float auroraA = 0.55F + 0.25F * (float)Math.sin(time * 0.31F), auroraB = 0.4F + 0.25F * (float)Math.sin(time * 0.23F + 1.7F);
        drawLayer(g, AURORA_A, w, h, -MARGIN[AURORA_A] + drift, 0F, auroraA, currentAlpha, fade);
        drawLayer(g, AURORA_B, w, h, -MARGIN[AURORA_B] - drift * 0.7F, 0F, auroraB, currentAlpha, fade);

        ModernStyle.fillGradient(g, 0, horizon - h / 5, w, horizon + h / 40, 0x0086C8E8, 0x4486C8E8);
        for (int layer = FAR; layer <= NEAR; layer++) {
            float m = MARGIN[layer];
            drawLayer(g, layer, w, h, -m + parallaxX * m, parallaxY * m * 0.25F, 1F, currentAlpha, fade);
        }
    }

    /** One layer from the current set, then - while it fades out - the same layer from the previous set on top. */
    private void drawLayer(GuiGraphicsExtractor g, int index, int w, int h, float x, float y, float opacity, float currentAlpha, float fade) {
        drawFitted(g, this.layers[index], this.builtW, this.builtH, w, h, x, y, opacity * currentAlpha);
        if (this.previous != null) drawFitted(g, this.previous[index], this.previousW, this.previousH, w, h, x, y, opacity * (1F - fade));
    }

    /**
     * Draws a layer painted for {@code bw}x{@code bh} at {@code w}x{@code h}. Same size: as is. Another size (a
     * repaint for this one is on its way): scaled uniformly - by the larger ratio, anchored to the bottom
     * centre - so it reads as a slight zoom rather than squashed mountains. The layers are wider than the
     * screen, so the zoom never uncovers an edge.
     */
    private static void drawFitted(GuiGraphicsExtractor g, @Nullable Layer layer, int bw, int bh, int w, int h, float x, float y, float opacity) {
        if (layer == null || bw <= 0 || bh <= 0) return;
        g.pose().pushMatrix();
        try {
            if (bw != w || bh != h) {
                float scale = Math.max(w / (float)bw, h / (float)bh);
                g.pose().translate(w / 2F, h);
                g.pose().scale(scale, scale);
                g.pose().translate(-bw / 2F, -bh);
            }
            g.pose().translate(0F, y);
            blit(g, layer, x, opacity);
        } finally {
            g.pose().popMatrix();
        }
    }

    static int horizon(int h) {
        return Math.round(h * 0.62F);
    }

    private void drawMoon(GuiGraphicsExtractor g, int w, int h, float parallaxX, float parallaxY) {
        float r = Math.max(9F, Math.min(22F, h * 0.034F));
        float cx = w * 0.86F - parallaxX * 3F, cy = h * 0.31F - parallaxY * 1.5F;
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, cx - r * 4.2F, cy - r * 4.2F, r * 8.4F, 0x2EBFE8FF);
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, cx - r * 1.9F, cy - r * 1.9F, r * 3.8F, 0x40DDF2FF);
        int ir = Math.round(r);
        ModernStyle.rounded(g, Math.round(cx) - ir, Math.round(cy) - ir, ir * 2, ir * 2, ir, 0xFFEEF6FB);
        ModernStyle.rounded(g, Math.round(cx - r * 0.35F), Math.round(cy - r * 0.2F), Math.round(r * 0.42F), Math.round(r * 0.42F), Math.round(r * 0.21F), 0x1A3B6A8C);
        ModernStyle.rounded(g, Math.round(cx + r * 0.18F), Math.round(cy + r * 0.25F), Math.round(r * 0.3F), Math.round(r * 0.3F), Math.round(r * 0.15F), 0x143B6A8C);
    }

    /** One streak every ~11s, crossing a random stretch of the upper sky in under a second. */
    private void drawShootingStar(GuiGraphicsExtractor g, int w, int h, float time) {
        float period = 11F;
        int cycle = (int)(time / period);
        float t = (time - cycle * period) / 0.9F;
        if (t < 0F || t > 1F) return;
        Random random = new Random(cycle * 7919L);
        float x0 = w * (0.15F + random.nextFloat() * 0.45F), y0 = h * (0.04F + random.nextFloat() * 0.14F);
        float len = w * 0.22F;
        float headX = x0 + len * t, headY = y0 + len * 0.35F * t;
        float fade = (float)Math.sin(t * Math.PI);
        for (int i = 0; i < 14; i++) {
            float k = i / 14F;
            float px = headX - len * 0.3F * k, py = headY - len * 0.105F * k;
            int alpha = Math.round(255 * fade * (1F - k) * 0.85F);
            float size = 2.6F * (1F - k * 0.7F);
            ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, px - size / 2F, py - size / 2F, size, alpha << 24 | 0xF4FAFF);
        }
    }

    private static void blit(GuiGraphicsExtractor g, Layer layer, float x, float opacity) {
        if (layer == null) return;
        int alpha = Math.round(255 * Math.max(0F, Math.min(1F, opacity)));
        int color = ModernStyle.a(alpha << 24 | 0xFFFFFF);
        if ((color >>> 24) == 0) return;
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, layer.top());
            g.blit(RenderPipelines.GUI_TEXTURED, layer.id(), 0, 0, 0, 0, layer.w(), layer.h(),
                layer.texW(), layer.texH(), layer.texW(), layer.texH(), color);
        } finally {
            g.pose().popMatrix();
        }
    }

    // --- building ----------------------------------------------------------------------------------

    private void ensure(int w, int h) {
        int scale = Minecraft.getInstance().getWindow().getGuiScale();
        CompletableFuture<Built> job = this.pending;
        if (job != null && job.isDone()) {
            this.pending = null;
            try {
                install(job.join());
            } catch (RuntimeException failure) {
                // Don't retry the same size every frame; a different size (or scale) will try again.
                LOGGER.error("SigmaModern scene painting failed", failure);
            }
        }
        Key key = new Key(w, h, scale);
        if (key.equals(new Key(this.builtW, this.builtH, this.builtScale)) || this.pending != null || key.equals(this.failed)) return;
        // One build at a time: a size that changes again mid-build (dragging the window edge) is picked up when
        // this one lands, so a drag never queues a painting per frame.
        this.pending = CompletableFuture.supplyAsync(() -> build(key), PAINTER);
        this.pending.whenComplete((built, failure) -> {
            if (failure != null) this.failed = key;
        });
    }

    private void install(Built built) {
        // The outgoing set stays for the crossfade; one still fading from an even earlier install goes now.
        if (this.previous != null) for (Layer layer : this.previous) if (layer != null) ModernSkiaRaster.release(layer.id());
        boolean hadLayers = this.builtW > 0;
        this.previous = hadLayers ? this.layers.clone() : null;
        this.previousW = this.builtW;
        this.previousH = this.builtH;
        this.firstInstall = !hadLayers;
        this.installedAt = System.nanoTime();
        for (Prepared prepared : built.layers()) {
            Identifier id = Identifier.withDefaultNamespace("sigma/modern_scene/" + prepared.name() + "_" + this.serial++);
            ModernSkiaRaster.registerColor(id, prepared.pixels());
            this.layers[prepared.index()] = new Layer(id, prepared.top(), prepared.w(), prepared.h(), prepared.texW(), prepared.texH());
        }
        this.builtW = built.key().w();
        this.builtH = built.key().h();
        this.builtScale = built.key().scale();
        if (Boolean.getBoolean("sigma.debug.frameTiming")) {
            LOGGER.info("Sigma debug: scene layers installed for {}x{} @{}", this.builtW, this.builtH, this.builtScale);
        }
    }

    /** Paints every layer for {@code key}. Runs on {@link #PAINTER}: no game state, no textures. */
    private static Built build(Key key) {
        long start = System.nanoTime();
        int w = key.w(), h = key.h(), scale = key.scale();
        int horizon = horizon(h);
        Prepared[] prepared = new Prepared[5];
        int farTop = horizon - Math.round(h * 0.33F);
        prepared[0] = paint(FAR, "far", farTop, w + MARGIN[FAR] * 2, h - farTop, scale, (c, lw, lh) ->
            mountains(c, lw, lh, 0xF1A, 10, horizon - farTop + h * 0.05F, h * 0.02F, h * 0.17F,
                0xFF4E7D9D, 0xFF3F6C8B, 0xFFD2E6F3, 0xFFA9C7DB));
        int midTop = horizon - Math.round(h * 0.25F);
        prepared[1] = paint(MID, "mid", midTop, w + MARGIN[MID] * 2, h - midTop, scale, (c, lw, lh) ->
            mountains(c, lw, lh, 0x3D7, 6, horizon - midTop + h * 0.13F, h * 0.01F, h * 0.14F,
                0xFF28506F, 0xFF1D4058, 0xFFE4F2FA, 0xFFB3D0E3));
        prepared[2] = paint(NEAR, "near", horizon, w + MARGIN[NEAR] * 2, h - horizon, scale, ModernScene::snowfield);
        int auroraTop = Math.round(h * 0.02F), auroraH = Math.round(h * 0.42F);
        prepared[3] = paint(AURORA_A, "aurora_a", auroraTop, w + MARGIN[AURORA_A] * 2, auroraH, scale, (c, lw, lh) ->
            aurora(c, lw, lh, 0.42F, 0.0042F, 1.3F, new int[]{0x0060F0D0, 0xA860F0D0, 0xB070D8FF, 0x98A48CFF, 0x0060F0D0}));
        prepared[4] = paint(AURORA_B, "aurora_b", auroraTop, w + MARGIN[AURORA_B] * 2, auroraH, scale, (c, lw, lh) ->
            aurora(c, lw, lh, 0.62F, 0.0061F, 4.1F, new int[]{0x0078C8FF, 0x7078C8FF, 0x8850F0C8, 0x0050F0C8}));
        if (Boolean.getBoolean("sigma.debug.frameTiming")) {
            LOGGER.info("Sigma debug: scene painted for {}x{} @{} in {} ms (off-thread)", w, h, scale, (System.nanoTime() - start) / 1_000_000);
        }
        return new Built(key, prepared);
    }

    private interface Painter {
        void paint(Canvas canvas, float layerW, float layerH);
    }

    private static Prepared paint(int index, String name, int top, int lw, int lh, int scale, Painter painter) {
        int th = lh + BLEED;
        float rs = Math.min(scale, Math.min(4096F / lw, 2048F / th));
        int texW = Math.max(1, (int)Math.ceil(lw * rs)), texH = Math.max(1, (int)Math.ceil(th * rs));
        Consumer<Canvas> draw = canvas -> {
            canvas.scale(rs, rs);
            painter.paint(canvas, lw, lh);
        };
        return new Prepared(index, name, top, lw, th, texW, texH, ModernSkiaRaster.prepareColor(ModernSkiaRaster.render(texW, texH, draw)));
    }

    private static void polygon(Canvas c, int argb, float... xy) {
        try (Paint paint = new Paint().setColor(argb).setAntiAlias(true);
             PathBuilder builder = new PathBuilder().addPolygon(xy, true); Path path = builder.detach()) {
            c.drawPath(path, paint);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * A low-poly range: valleys and apexes, each apex splitting a lit left face from a shadowed right face,
     * capped with snow whose lower edge zigzags. All heights are layer-local y: apexes land between
     * {@code peakTop} (the tallest possible) and {@code minRise} above {@code base}.
     */
    private static void mountains(Canvas c, float lw, float lh, long seed, int peaks, float base,
                                  float peakTop, float minRise, int rock, int rockShade, int snow, int snowShade) {
        Random r = new Random(seed);
        float step = lw / peaks;
        float[] vx = new float[peaks + 1], vy = new float[peaks + 1], ax = new float[peaks], ay = new float[peaks];
        float apexLow = base - minRise;
        for (int i = 0; i <= peaks; i++) {
            vx[i] = i == 0 ? 0 : i == peaks ? lw : i * step + (r.nextFloat() - 0.5F) * step * 0.35F;
            vy[i] = base - r.nextFloat() * minRise * 0.45F;
        }
        for (int i = 0; i < peaks; i++) {
            ax[i] = lerp(vx[i], vx[i + 1], 0.32F + r.nextFloat() * 0.36F);
            ay[i] = lerp(peakTop, apexLow, (float)Math.pow(r.nextFloat(), 0.8));
        }
        float[] outline = new float[(peaks * 2 + 1) * 2 + 4];
        int k = 0;
        outline[k++] = 0; outline[k++] = lh + BLEED + 4;
        for (int i = 0; i < peaks; i++) {
            outline[k++] = vx[i]; outline[k++] = vy[i];
            outline[k++] = ax[i]; outline[k++] = ay[i];
        }
        outline[k++] = vx[peaks]; outline[k++] = vy[peaks];
        outline[k++] = lw; outline[k] = lh + BLEED + 4;
        polygon(c, rock, outline);

        for (int i = 0; i < peaks; i++) {
            float x = ax[i], y = ay[i], rx = vx[i + 1], ry = vy[i + 1], lx = vx[i], ly = vy[i];
            polygon(c, rockShade, x, y, rx, ry, rx, lh + BLEED + 4, lerp(x, rx, 0.12F), lh + BLEED + 4);
            float t = 0.26F + r.nextFloat() * 0.12F;
            float leftX = lerp(x, lx, t), leftY = lerp(y, ly, t), rightX = lerp(x, rx, t), rightY = lerp(y, ry, t);
            float drop = (Math.max(leftY, rightY) - y) * 0.28F;
            float p1x = lerp(leftX, rightX, 0.24F), p1y = lerp(leftY, rightY, 0.24F) + drop;
            float p2x = lerp(leftX, rightX, 0.44F), p2y = lerp(leftY, rightY, 0.44F) - drop * 0.35F;
            float p3x = lerp(leftX, rightX, 0.62F), p3y = lerp(leftY, rightY, 0.62F) + drop * 0.9F;
            float p4x = lerp(leftX, rightX, 0.82F), p4y = lerp(leftY, rightY, 0.82F) - drop * 0.2F;
            polygon(c, snow, x, y, leftX, leftY, p1x, p1y, p2x, p2y, p3x, p3y, p4x, p4y, rightX, rightY);
            polygon(c, snowShade, x, y, rightX, rightY, p4x, p4y, p3x, p3y);
        }
    }

    /** Height of the near snowfield's first drift at {@code x}, in layer-local units. */
    private static float drift(float x, float lh, float phase, float level) {
        return lh * level + (float)Math.sin(x * 0.011F + phase) * lh * 0.05F + (float)Math.sin(x * 0.027F + phase * 2.3F) * lh * 0.025F;
    }

    private static void snowfield(Canvas c, float lw, float lh) {
        fieldBand(c, lw, lh, 0.5F, 0.44F, 0xFFE9F4FA, 0xFFBBD4E4);
        Random r = new Random(0x7EE5);
        for (int i = 0; i < 26; i++) {
            float side = r.nextFloat();
            float x = side < 0.55F ? lw * (0.02F + r.nextFloat() * 0.26F) : lw * (0.70F + r.nextFloat() * 0.28F);
            float height = lh * (0.2F + r.nextFloat() * 0.26F);
            pine(c, x, drift(x, lh, 0.5F, 0.44F) + lh * 0.02F, height);
        }
        fieldBand(c, lw, lh, 2.1F, 0.72F, 0xFFD9EAF4, 0xFFA7C4D8);
    }

    private static void fieldBand(Canvas c, float lw, float lh, float phase, float level, int top, int bottom) {
        int n = Math.max(8, (int)(lw / 4F));
        float[] xy = new float[(n + 1) * 2 + 4];
        int k = 0;
        for (int i = 0; i <= n; i++) {
            float x = lw * i / n;
            xy[k++] = x;
            xy[k++] = drift(x, lh, phase, level);
        }
        xy[k++] = lw; xy[k++] = lh + BLEED + 4;
        xy[k++] = 0; xy[k] = lh + BLEED + 4;
        try (Shader shader = Shader.makeLinearGradient(0, lh * level - lh * 0.08F, 0, lh, new int[]{top, bottom});
             Paint paint = new Paint().setAntiAlias(true).setShader(shader);
             PathBuilder builder = new PathBuilder().addPolygon(xy, true); Path path = builder.detach()) {
            c.drawPath(path, paint);
        }
    }

    /** A three-tier flat pine: a lit left half, a shaded right half, and a snow sliver on each tier. */
    private static void pine(Canvas c, float x, float groundY, float height) {
        float width = height * 0.46F;
        polygon(c, 0xFF0B2230, x - width * 0.05F, groundY, x + width * 0.05F, groundY, x + width * 0.05F, groundY - height * 0.16F, x - width * 0.05F, groundY - height * 0.16F);
        for (int tier = 0; tier < 3; tier++) {
            float tierBottom = groundY - height * (0.12F + tier * 0.27F);
            float tierTop = tierBottom - height * (0.5F - tier * 0.08F);
            float half = width * (0.5F - tier * 0.12F);
            polygon(c, 0xFF15384C, x, tierTop, x - half, tierBottom, x, tierBottom);
            polygon(c, 0xFF0D2839, x, tierTop, x, tierBottom, x + half, tierBottom);
            float sy = tierTop + (tierBottom - tierTop) * 0.28F;
            polygon(c, 0xFFDDEDF6, x, tierTop, x - half * 0.3F, sy, x - half * 0.08F, sy - (sy - tierTop) * 0.2F, x + half * 0.12F, sy + (sy - tierTop) * 0.05F, x + half * 0.28F, sy);
        }
    }

    /**
     * A soft aurora ribbon: a wavy band of varying thickness, filled with a hue gradient running along it,
     * blurred, plus a thinner brighter core. {@code level} is the ribbon's centre as a fraction of height.
     */
    private static void aurora(Canvas c, float lw, float lh, float level, float frequency, float phase, int[] colors) {
        int n = Math.max(8, (int)(lw / 6F));
        float[] band = new float[(n + 1) * 4];
        float[] core = new float[n * 2];
        float coreStartY = 0;
        for (int i = 0; i <= n; i++) {
            float x = lw * i / n;
            float center = lh * level + (float)Math.sin(x * frequency + phase) * lh * 0.16F + (float)Math.sin(x * frequency * 2.7F + phase * 1.9F) * lh * 0.06F;
            float thickness = lh * (0.08F + 0.07F * (0.5F + 0.5F * (float)Math.sin(x * frequency * 1.6F + phase * 0.7F)));
            band[i * 2] = x;
            band[i * 2 + 1] = center - thickness;
            band[(n + 1) * 4 - 2 - i * 2] = x;
            band[(n + 1) * 4 - 1 - i * 2] = center + thickness * 0.45F;
            if (i == 0) {
                coreStartY = center;
            } else {
                core[(i - 1) * 2] = x;
                core[(i - 1) * 2 + 1] = center;
            }
        }
        try (Shader shader = Shader.makeLinearGradient(0, 0, lw, 0, colors);
             MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, lh * 0.05F);
             Paint glow = new Paint().setAntiAlias(true).setShader(shader).setMaskFilter(blur);
             PathBuilder bandBuilder = new PathBuilder().addPolygon(band, true); Path bandPath = bandBuilder.detach()) {
            c.drawPath(bandPath, glow);
        }
        try (Shader shader = Shader.makeLinearGradient(0, 0, lw, 0, colors);
             MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, lh * 0.012F);
             Paint line = new Paint().setAntiAlias(true).setShader(shader).setMaskFilter(blur)
                 .setMode(io.github.humbleui.skija.PaintMode.STROKE).setStrokeWidth(lh * 0.012F);
             PathBuilder coreBuilder = new PathBuilder().moveTo(0, coreStartY).polylineTo(core); Path corePath = coreBuilder.detach()) {
            c.drawPath(corePath, line);
        }
    }
}
