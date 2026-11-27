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
 * SigmaModern's flat-illustration backdrop: a gradient sky with stars, a moon and the odd shooting star,
 * two slowly drifting aurora ribbons, two low-poly mountain ranges (lit and shadowed faces, jagged snowcaps)
 * and a snowfield with pines - or the same machinery repainted as one of the other
 * {@link ModernSceneTheme themes}: a golden sunrise, a violet twilight under a crescent, a bright arctic day,
 * a pink sakura evening with drifting petals, an alien nebula with glowing crystal ridges, or a blood moon
 * with rising embers. The theme comes from {@link ModernSceneSettings} (the main menu's Scene button), with
 * {@code -Dsigma.scene=<theme>} as a launch-time override.
 *
 * <p>Everything that isn't animated per-star is painted by Skia into textures once per window size - never
 * per frame - each a little wider than the screen so the layers can slide apart for mouse parallax.
 * Rasters are capped at 4096×2048 and simply upscaled past that. Painting takes the better part of a second
 * at large sizes, so it runs on a background thread: until a new size's layers are ready, the previous ones
 * are drawn zoomed to cover it, then the new set crossfades in (a maximize or restore used to freeze the
 * window while Windows stretched the last frame). A theme change goes through the same path: the new theme's
 * layers paint off-thread and, once they land, the whole scene - sky, celestial body and layers together -
 * crossfades from the old theme to the new one. Shared by every SigmaModern screen that shows no world, so
 * the main menu and ClickGUI never paint the same scene twice.</p>
 */
final class ModernScene {
    private static final ModernScene SHARED = new ModernScene();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Sigma/ModernScene");

    /** What drifts in front of the scene; read by {@link ModernSnow}. */
    enum Particles {
        /** Soft flakes, roughly one in eight near ones a slowly spinning crystal. */
        SNOW(1F, ModernIcons.Icon.CRYSTAL, 0.55F, 0.13F, 1F, false, 1F),
        /** Slow, tumbling cherry petals - nearly every flake past the farthest few is a petal. */
        PETALS(0.5F, ModernIcons.Icon.PETAL, 0.08F, 0.9F, 1.15F, false, 3.2F),
        /** Small glowing sparks rising from below, flickering. */
        EMBERS(-0.55F, null, 1F, 0F, 0.7F, true, 0F),
        /** Barely falling motes, some of them four-point sparkles, twinkling. */
        STARDUST(0.16F, ModernIcons.Icon.SPARKLE, 0.35F, 0.3F, 0.85F, true, 0.4F);

        /** Fall speed multiplier; negative rises. */
        final float fall;
        /** The sprite some particles use instead of a soft dot, or null for dots only. */
        final ModernIcons.@Nullable Icon shape;
        /** Only particles at least this near (depth 0..1) can use {@link #shape}... */
        final float shapeMinDepth;
        /** ...and of those, this share does. */
        final float shapeShare;
        final float size;
        final boolean twinkle;
        final float spin;

        Particles(float fall, ModernIcons.@Nullable Icon shape, float shapeMinDepth, float shapeShare, float size, boolean twinkle, float spin) {
            this.fall = fall;
            this.shape = shape;
            this.shapeMinDepth = shapeMinDepth;
            this.shapeShare = shapeShare;
            this.size = size;
            this.twinkle = twinkle;
            this.spin = spin;
        }
    }

    private enum Body { MOON, CRESCENT, SUN }

    /** The moon or sun: where it sits (fractions of the screen), how big relative to the default, and its colors. */
    private record Celestial(Body body, float x, float y, float scale, int disc, int haloOuter, int haloInner, int craterRgb) {}

    /**
     * One full palette for a scene theme: sky, horizon glow, stars, the moon or sun, the two mountain ranges
     * (rock, rock shade, snow, snow shade each), the field's two drift bands, the two aurora/cloud ribbons,
     * the trees (lit, shade, trunk, snow), what drifts in front and in what color, the dark "ink" menus
     * darken toward over this scene, and a seed that reshapes the ranges (0 keeps the original silhouettes).
     */
    private record Palette(int skyTop, int skyMid, int skyHorizon, int glowTop, int glowBottom,
                           float starAlpha, int starRgb, Celestial celestial, float auroraOpacity,
                           int[] far, int[] mid, int snowTop1, int snowBottom1, int snowTop2, int snowBottom2,
                           int[] auroraA, int[] auroraB, int[] trees, Particles particles, int particleRgb,
                           int inkRgb, long shapeSeed) {}

    private static final int[] PINES = {0xFF15384C, 0xFF0D2839, 0xFF0B2230, 0xFFDDEDF6};
    private static final Palette NIGHT = new Palette(
            0xFF050F1A, 0xFF0C2438, 0xFF356A8F, 0x0086C8E8, 0x4486C8E8, 1F, 0xE8F4FF,
            new Celestial(Body.MOON, 0.86F, 0.31F, 1F, 0xFFEEF6FB, 0x2EBFE8FF, 0x40DDF2FF, 0x3B6A8C), 1F,
            new int[]{0xFF4E7D9D, 0xFF3F6C8B, 0xFFD2E6F3, 0xFFA9C7DB},
            new int[]{0xFF28506F, 0xFF1D4058, 0xFFE4F2FA, 0xFFB3D0E3},
            0xFFE9F4FA, 0xFFBBD4E4, 0xFFD9EAF4, 0xFFA7C4D8,
            new int[]{0x0060F0D0, 0xA860F0D0, 0xB070D8FF, 0x98A48CFF, 0x0060F0D0},
            new int[]{0x0078C8FF, 0x7078C8FF, 0x8850F0C8, 0x0050F0C8},
            PINES, Particles.SNOW, 0xF2FAFF, 0x050F1A, 0L);
    private static final Palette SUNRISE = new Palette(
            0xFF1B1440, 0xFF6B3A5E, 0xFFE8915A, 0x00FFB35C, 0x66FFB35C, 0.3F, 0xE8F4FF,
            new Celestial(Body.SUN, 0.80F, 0.48F, 1F, 0xFFFFE9B0, 0x30FFD98A, 0x50FFC46E, 0), 0.55F,
            new int[]{0xFF8A6A8C, 0xFF77587A, 0xFFF5DCC8, 0xFFE8BFA8},
            new int[]{0xFF5E4266, 0xFF4C3454, 0xFFF2D7C2, 0xFFDDB894},
            0xFFF6E3D0, 0xFFE0BFA8, 0xFFEED9C0, 0xFFD0A890,
            new int[]{0x00FF9E6E, 0xA8FF9E6E, 0xB0FFB35C, 0x98FF8FB0, 0x00FF9E6E},
            new int[]{0x00FFB35C, 0x70FFB35C, 0x88FF7E9E, 0x00FFB35C},
            PINES, Particles.SNOW, 0xF2FAFF, 0x1B1440, 0L);
    /** Violet-to-rose afterglow, a crescent moon, magenta ribbons and lilac snow. */
    private static final Palette DUSK = new Palette(
            0xFF120A2E, 0xFF46245E, 0xFFC2577A, 0x00FF7A8A, 0x55FF7A8A, 0.7F, 0xFFF0FA,
            new Celestial(Body.CRESCENT, 0.84F, 0.27F, 1.1F, 0xFFFFF1E0, 0x30FFB0C8, 0x40FFD8E0, 0), 0.5F,
            new int[]{0xFF5B3F78, 0xFF4A3266, 0xFFD9C3E8, 0xFFB79FD0},
            new int[]{0xFF3A2752, 0xFF2C1D42, 0xFFE6D3EE, 0xFFBFA6D6},
            0xFFEADCF2, 0xFFC3AED6, 0xFFDCC8EA, 0xFFAE93C6,
            new int[]{0x00C86CFF, 0x90C86CFF, 0xA0FF7AB8, 0x806C7CFF, 0x00C86CFF},
            new int[]{0x00FF9AAE, 0x60FF9AAE, 0x78A070FF, 0x00A070FF},
            new int[]{0xFF2A1E44, 0xFF1E1534, 0xFF181028, 0xFFE3D5F0}, Particles.SNOW, 0xF6EEFF, 0x120A2E, 0x2D5CL);
    /** Clear noon over the ice: blue sky, a high white sun, wisps of cloud, blinding snow and green pines. */
    private static final Palette ARCTIC_DAY = new Palette(
            0xFF2F7FC4, 0xFF6FB2E3, 0xFFCDE9F7, 0x00FFFFFF, 0x66FFFFFF, 0F, 0xFFFFFF,
            new Celestial(Body.SUN, 0.84F, 0.17F, 0.9F, 0xFFFFFDF2, 0x40FFFFFF, 0x60FFF6D8, 0), 0.45F,
            new int[]{0xFF8FB4CF, 0xFF7AA2BF, 0xFFF4FAFE, 0xFFD3E4F0},
            new int[]{0xFF5E8AAD, 0xFF4B7698, 0xFFFFFFFF, 0xFFD6E6F2},
            0xFFFFFFFF, 0xFFDCEBF5, 0xFFF7FBFE, 0xFFCFE2EF,
            new int[]{0x00FFFFFF, 0x90FFFFFF, 0xA0F4FAFF, 0x80FFFFFF, 0x00FFFFFF},
            new int[]{0x00FFFFFF, 0x60FFFFFF, 0x78EAF4FF, 0x00EAF4FF},
            new int[]{0xFF2E5E4E, 0xFF214A3D, 0xFF3A2C22, 0xFFF4FAFE}, Particles.SNOW, 0xFFFFFF, 0x0B2A45, 0x0A1CL);
    /** A spring evening: plum-to-pink sky, a low rosy sun, blossom trees on a petal-strewn meadow. */
    private static final Palette SAKURA = new Palette(
            0xFF2B1D4A, 0xFF8E5A8F, 0xFFF4B6C2, 0x00FFD1DC, 0x66FFD1DC, 0.25F, 0xFFF0F6,
            new Celestial(Body.SUN, 0.82F, 0.42F, 1F, 0xFFFFE6EC, 0x38FFC0D0, 0x50FFD6E0, 0), 0.45F,
            new int[]{0xFF9C7FA6, 0xFF866A92, 0xFFF6E6EE, 0xFFE2C8D8},
            new int[]{0xFF6E5482, 0xFF5A4470, 0xFFF3DDE8, 0xFFD7BACB},
            0xFFF7DCE6, 0xFFE3B9CB, 0xFFEFCFDC, 0xFFD49CB4,
            new int[]{0x00FFB7D0, 0x90FFB7D0, 0xA0FFD0E0, 0x80E8A8FF, 0x00FFB7D0},
            new int[]{0x00FFC8DC, 0x60FFC8DC, 0x78FFA8C8, 0x00FFA8C8},
            new int[]{0xFFE89AB8, 0xFFC97A9A, 0xFF4A2E3A, 0xFFFFE4EE}, Particles.PETALS, 0xFFC6D8, 0x2A1530, 0x5A6BL);
    /** Deep space: near-black violet sky, a pale moon, vivid nebula ribbons over dark ridges with glowing crystal caps. */
    private static final Palette NEBULA = new Palette(
            0xFF04030C, 0xFF140E33, 0xFF3A2470, 0x00B07CFF, 0x50B07CFF, 1F, 0xF0E8FF,
            new Celestial(Body.MOON, 0.80F, 0.26F, 1.25F, 0xFFE9DEFF, 0x30B48CFF, 0x45D2BEFF, 0x6A4FB0), 1F,
            new int[]{0xFF2A2350, 0xFF201A42, 0xFF8C7BD6, 0xFF6E5FB8},
            new int[]{0xFF17122F, 0xFF100C24, 0xFFB59CFF, 0xFF8A73E0},
            0xFF2A2152, 0xFF15102E, 0xFF1E1840, 0xFF0C0920,
            new int[]{0x00FF5FD2, 0xA0FF5FD2, 0xB08A6CFF, 0x9850D8FF, 0x00FF5FD2},
            new int[]{0x0050E0FF, 0x7050E0FF, 0x88B070FF, 0x00B070FF},
            new int[]{0xFF3C2F78, 0xFF2A2058, 0xFF140F2A, 0xFFC8B6FF}, Particles.STARDUST, 0xDCCBFF, 0x05040F, 0x7B1DL);
    /** A huge red moon over crimson ridges, dark pines and ash-pale snow, with embers rising. */
    private static final Palette BLOOD_MOON = new Palette(
            0xFF12040A, 0xFF3A0B19, 0xFF8A2A2E, 0x00FF5A4A, 0x55FF5A4A, 0.5F, 0xFFE4E0,
            new Celestial(Body.MOON, 0.80F, 0.30F, 1.6F, 0xFFE0634A, 0x40FF3A2A, 0x58FF6A4A, 0x6A1A10), 0.4F,
            new int[]{0xFF4A2030, 0xFF3B1826, 0xFFC9A3AA, 0xFFA87E88},
            new int[]{0xFF2E1018, 0xFF220B12, 0xFFD7B3B8, 0xFFB08890},
            0xFFD8BFC2, 0xFFA88A90, 0xFFC6A9AE, 0xFF93757C,
            new int[]{0x00FF4A3A, 0x90FF4A3A, 0xA0FF7A4A, 0x80C02A50, 0x00FF4A3A},
            new int[]{0x00FF6A4A, 0x60FF6A4A, 0x78D03A3A, 0x00D03A3A},
            new int[]{0xFF2A0F17, 0xFF1C0910, 0xFF14060B, 0xFFD9BDC1}, Particles.EMBERS, 0xFFA060, 0x14040A, 0x0B1EL);

    private static Palette palette(ModernSceneTheme theme) {
        return switch (theme) {
            case NIGHT -> NIGHT;
            case SUNRISE -> SUNRISE;
            case DUSK -> DUSK;
            case ARCTIC_DAY -> ARCTIC_DAY;
            case SAKURA -> SAKURA;
            case NEBULA -> NEBULA;
            case BLOOD_MOON -> BLOOD_MOON;
        };
    }

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
    private @Nullable ModernSceneTheme previousTheme;
    private long installedAt;
    private boolean firstInstall;
    private final float[] starX = new float[90], starY = new float[90], starPhase = new float[90], starSize = new float[90];
    private int builtW = -1, builtH = -1, builtScale = -1;
    private @Nullable ModernSceneTheme builtTheme;
    private long serial;

    private record Layer(Identifier id, int top, int w, int h, int texW, int texH) {}
    private record Key(int w, int h, int scale, ModernSceneTheme theme) {}
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

    /** The theme the user picked - what the scene is painting or about to crossfade to. */
    private static ModernSceneTheme requested() {
        return com.mentalfrostbyte.Client.getInstance().getModernSceneSettings().get();
    }

    /** The theme on screen right now: the installed layer set's, or - before anything is installed - the requested one. */
    private Palette shown() {
        return palette(this.builtTheme != null ? this.builtTheme : requested());
    }

    /** 0..1 progress of the crossfade from {@link #previousTheme}, or 1 when no theme change is fading. */
    private float themeFade() {
        if (this.previous == null || this.previousTheme == null || this.previousTheme == this.builtTheme) return 1F;
        return Math.min(1F, (System.nanoTime() - this.installedAt) / 1_000_000_000F / 0.35F);
    }

    /** What should drift in front of the scene right now. */
    Particles particles() {
        return shown().particles();
    }

    /** The particles' color (RGB, no alpha), following the theme crossfade. */
    int particleRgb() {
        float t = themeFade();
        int rgb = shown().particleRgb();
        return t >= 1F ? rgb : ModernStyle.mix(0xFF000000 | palette(this.previousTheme).particleRgb(), 0xFF000000 | rgb, t) & 0xFFFFFF;
    }

    /** The dark color menus darken toward over this scene (RGB, no alpha), following the theme crossfade. */
    int ink() {
        float t = themeFade();
        int rgb = shown().inkRgb();
        return t >= 1F ? rgb : ModernStyle.mix(0xFF000000 | palette(this.previousTheme).inkRgb(), 0xFF000000 | rgb, t) & 0xFFFFFF;
    }

    /** Whether the near field is light (snow) rather than dark, so ink drawn on it should be dark. */
    boolean groundIsLight() {
        int c = shown().snowTop2();
        float luminance = 0.2126F * (c >> 16 & 0xFF) + 0.7152F * (c >> 8 & 0xFF) + 0.0722F * (c & 0xFF);
        return luminance > 128F;
    }

    /**
     * @param parallaxX -1..1, from the mouse's horizontal position
     * @param parallaxY -1..1, from the mouse's vertical position
     * @param time      seconds, for stars, aurora drift and shooting stars
     */
    void render(GuiGraphicsExtractor g, int w, int h, float parallaxX, float parallaxY, float time) {
        ensure(w, h);
        Palette theme = shown();
        float themeT = themeFade();
        Palette from = themeT < 1F ? palette(this.previousTheme) : theme;
        int horizon = horizon(h);
        int skyTop = ModernStyle.mix(from.skyTop(), theme.skyTop(), themeT);
        int skyMid = ModernStyle.mix(from.skyMid(), theme.skyMid(), themeT);
        int skyHorizon = ModernStyle.mix(from.skyHorizon(), theme.skyHorizon(), themeT);
        ModernStyle.fillGradient(g, 0, 0, w, Math.round(horizon * 0.55F), skyTop, skyMid);
        ModernStyle.fillGradient(g, 0, Math.round(horizon * 0.55F), w, horizon + h / 12, skyMid, skyHorizon);
        ModernStyle.fill(g, 0, horizon + h / 12, w, h, skyHorizon);

        float starAlpha = from.starAlpha() + (theme.starAlpha() - from.starAlpha()) * themeT;
        int starRgb = ModernStyle.mix(0xFF000000 | from.starRgb(), 0xFF000000 | theme.starRgb(), themeT) & 0xFFFFFF;
        if (starAlpha > 0.001F) {
            for (int i = 0; i < this.starX.length; i++) {
                float twinkle = 0.5F + 0.5F * (float)Math.sin(time * (0.8F + (i % 7) * 0.23F) + this.starPhase[i]);
                float depthFade = 1F - this.starY[i] / 0.6F;
                int alpha = Math.round(255 * (0.18F + 0.62F * twinkle * twinkle) * depthFade * starAlpha);
                float sx = this.starX[i] * w - parallaxX * 2F, sy = this.starY[i] * h - parallaxY;
                ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, sx, sy, this.starSize[i], alpha << 24 | starRgb);
            }
        }
        // The outgoing theme's moon or sun fades out while the incoming one fades in.
        if (themeT < 1F) {
            try (var out = ModernStyle.alphaScope(1F - themeT)) {
                drawSky(g, from, w, h, parallaxX, parallaxY, time, horizon);
            }
        }
        try (var in = ModernStyle.alphaScope(themeT)) {
            drawSky(g, theme, w, h, parallaxX, parallaxY, time, horizon);
        }

        // A new layer set fades in over the old one (or, the first time, over the bare sky); the old one is
        // released once it has faded out.
        float fade = Math.min(1F, (System.nanoTime() - this.installedAt) / 1_000_000_000F / 0.35F);
        if (fade >= 1F && this.previous != null) {
            for (Layer layer : this.previous) if (layer != null) ModernSkiaRaster.release(layer.id());
            this.previous = null;
            this.previousTheme = null;
        }
        float currentAlpha = this.previous == null && this.firstInstall ? fade : 1F;

        float drift = (float)Math.sin(time * 0.045F) * 70F;
        float auroraA = 0.55F + 0.25F * (float)Math.sin(time * 0.31F),
              auroraB = 0.4F + 0.25F * (float)Math.sin(time * 0.23F + 1.7F);
        drawLayer(g, AURORA_A, w, h, -MARGIN[AURORA_A] + drift, 0F, auroraA * theme.auroraOpacity(), auroraA * from.auroraOpacity(), currentAlpha, fade);
        drawLayer(g, AURORA_B, w, h, -MARGIN[AURORA_B] - drift * 0.7F, 0F, auroraB * theme.auroraOpacity(), auroraB * from.auroraOpacity(), currentAlpha, fade);

        ModernStyle.fillGradient(g, 0, horizon - h / 5, w, horizon + h / 40,
            ModernStyle.mix(from.glowTop(), theme.glowTop(), themeT), ModernStyle.mix(from.glowBottom(), theme.glowBottom(), themeT));
        for (int layer = FAR; layer <= NEAR; layer++) {
            float m = MARGIN[layer];
            drawLayer(g, layer, w, h, -m + parallaxX * m, parallaxY * m * 0.25F, 1F, 1F, currentAlpha, fade);
        }
    }

    /** One theme's moon or sun, and - at night - its shooting star. */
    private void drawSky(GuiGraphicsExtractor g, Palette theme, int w, int h, float parallaxX, float parallaxY, float time, int horizon) {
        Celestial body = theme.celestial();
        if (body.body() != Body.SUN && theme.starAlpha() > 0.5F) drawShootingStar(g, w, h, time);
        if (body.body() == Body.SUN) drawSun(g, body, w, h, parallaxX, parallaxY);
        else drawMoon(g, theme, body, w, h, parallaxX, parallaxY, horizon);
    }

    /** One layer from the current set, then - while it fades out - the same layer from the previous set on top. */
    private void drawLayer(GuiGraphicsExtractor g, int index, int w, int h, float x, float y, float opacity, float previousOpacity, float currentAlpha, float fade) {
        drawFitted(g, this.layers[index], this.builtW, this.builtH, w, h, x, y, opacity * currentAlpha);
        if (this.previous != null) drawFitted(g, this.previous[index], this.previousW, this.previousH, w, h, x, y, previousOpacity * (1F - fade));
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

    private void drawMoon(GuiGraphicsExtractor g, Palette theme, Celestial body, int w, int h, float parallaxX, float parallaxY, int horizon) {
        float r = Math.max(9F, Math.min(22F, h * 0.034F)) * body.scale();
        float cx = w * body.x() - parallaxX * 3F, cy = h * body.y() - parallaxY * 1.5F;
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, cx - r * 4.2F, cy - r * 4.2F, r * 8.4F, body.haloOuter());
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, cx - r * 1.9F, cy - r * 1.9F, r * 3.8F, body.haloInner());
        int ir = Math.round(r);
        ModernStyle.rounded(g, Math.round(cx) - ir, Math.round(cy) - ir, ir * 2, ir * 2, ir, body.disc());
        if (body.body() == Body.CRESCENT) {
            // Bite the disc with a slightly smaller one in the sky's own color at that height.
            float bx = cx + r * 0.42F, by = cy - r * 0.22F;
            int br = Math.round(r * 0.9F);
            float skyT = by / Math.max(1F, horizon * 0.55F);
            ModernStyle.rounded(g, Math.round(bx) - br, Math.round(by) - br, br * 2, br * 2, br, ModernStyle.mix(theme.skyTop(), theme.skyMid(), skyT));
            return;
        }
        ModernStyle.rounded(g, Math.round(cx - r * 0.35F), Math.round(cy - r * 0.2F), Math.round(r * 0.42F), Math.round(r * 0.42F), Math.round(r * 0.21F), 0x1A000000 | body.craterRgb());
        ModernStyle.rounded(g, Math.round(cx + r * 0.18F), Math.round(cy + r * 0.25F), Math.round(r * 0.3F), Math.round(r * 0.3F), Math.round(r * 0.15F), 0x14000000 | body.craterRgb());
    }

    /** A big warm disc with a soft halo: low over the horizon for the sunrise and sakura, high for the arctic day. */
    private void drawSun(GuiGraphicsExtractor g, Celestial body, int w, int h, float parallaxX, float parallaxY) {
        float r = Math.max(14F, Math.min(38F, h * 0.055F)) * body.scale();
        float cx = w * body.x() - parallaxX * 3F, cy = h * body.y() - parallaxY * 1.5F;
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, cx - r * 4.2F, cy - r * 4.2F, r * 8.4F, body.haloOuter());
        ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, cx - r * 1.9F, cy - r * 1.9F, r * 3.8F, body.haloInner());
        int ir = Math.round(r);
        ModernStyle.rounded(g, Math.round(cx) - ir, Math.round(cy) - ir, ir * 2, ir * 2, ir, body.disc());
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
        Key key = new Key(w, h, scale, requested());
        boolean upToDate = this.builtTheme != null && key.equals(new Key(this.builtW, this.builtH, this.builtScale, this.builtTheme));
        if (upToDate || this.pending != null || key.equals(this.failed)) return;
        // One build at a time: a size (or theme) that changes again mid-build (dragging the window edge, clicking
        // through themes) is picked up when this one lands, so a drag never queues a painting per frame.
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
        this.previousTheme = hadLayers ? this.builtTheme : null;
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
        this.builtTheme = built.key().theme();
        if (Boolean.getBoolean("sigma.debug.frameTiming")) {
            LOGGER.info("Sigma debug: scene layers installed for {}x{} @{} ({})", this.builtW, this.builtH, this.builtScale, this.builtTheme);
        }
    }

    /** Paints every layer for {@code key}. Runs on {@link #PAINTER}: no game state, no textures. */
    private static Built build(Key key) {
        long start = System.nanoTime();
        int w = key.w(), h = key.h(), scale = key.scale();
        Palette theme = palette(key.theme());
        long seed = theme.shapeSeed();
        int horizon = horizon(h);
        Prepared[] prepared = new Prepared[5];
        int farTop = horizon - Math.round(h * 0.33F);
        prepared[0] = paint(FAR, "far", farTop, w + MARGIN[FAR] * 2, h - farTop, scale, (c, lw, lh) ->
            mountains(c, lw, lh, 0xF1A ^ seed, 10, horizon - farTop + h * 0.05F, h * 0.02F, h * 0.17F,
                theme.far()[0], theme.far()[1], theme.far()[2], theme.far()[3]));
        int midTop = horizon - Math.round(h * 0.25F);
        prepared[1] = paint(MID, "mid", midTop, w + MARGIN[MID] * 2, h - midTop, scale, (c, lw, lh) ->
            mountains(c, lw, lh, 0x3D7 ^ seed * 31, 6, horizon - midTop + h * 0.13F, h * 0.01F, h * 0.14F,
                theme.mid()[0], theme.mid()[1], theme.mid()[2], theme.mid()[3]));
        prepared[2] = paint(NEAR, "near", horizon, w + MARGIN[NEAR] * 2, h - horizon, scale, (c, lw, lh) ->
            snowfield(c, lw, lh, theme.snowTop1(), theme.snowBottom1(), theme.snowTop2(), theme.snowBottom2(), theme.trees(), 0x7EE5 ^ seed * 17));
        int auroraTop = Math.round(h * 0.02F), auroraH = Math.round(h * 0.42F);
        prepared[3] = paint(AURORA_A, "aurora_a", auroraTop, w + MARGIN[AURORA_A] * 2, auroraH, scale, (c, lw, lh) ->
            aurora(c, lw, lh, 0.42F, 0.0042F, 1.3F, theme.auroraA()));
        prepared[4] = paint(AURORA_B, "aurora_b", auroraTop, w + MARGIN[AURORA_B] * 2, auroraH, scale, (c, lw, lh) ->
            aurora(c, lw, lh, 0.62F, 0.0061F, 4.1F, theme.auroraB()));
        if (Boolean.getBoolean("sigma.debug.frameTiming")) {
            LOGGER.info("Sigma debug: scene painted for {}x{} @{} ({}) in {} ms (off-thread)", w, h, scale, key.theme(), (System.nanoTime() - start) / 1_000_000);
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

    private static void snowfield(Canvas c, float lw, float lh, int bandTop1, int bandBottom1, int bandTop2, int bandBottom2, int[] trees, long seed) {
        fieldBand(c, lw, lh, 0.5F, 0.44F, bandTop1, bandBottom1);
        Random r = new Random(seed);
        for (int i = 0; i < 26; i++) {
            float side = r.nextFloat();
            float x = side < 0.55F ? lw * (0.02F + r.nextFloat() * 0.26F) : lw * (0.70F + r.nextFloat() * 0.28F);
            float height = lh * (0.2F + r.nextFloat() * 0.26F);
            pine(c, x, drift(x, lh, 0.5F, 0.44F) + lh * 0.02F, height, trees);
        }
        fieldBand(c, lw, lh, 2.1F, 0.72F, bandTop2, bandBottom2);
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

    /**
     * A three-tier flat pine: a lit left half, a shaded right half, and a snow sliver on each tier.
     * {@code colors} is {lit, shade, trunk, snow}.
     */
    private static void pine(Canvas c, float x, float groundY, float height, int[] colors) {
        float width = height * 0.46F;
        polygon(c, colors[2], x - width * 0.05F, groundY, x + width * 0.05F, groundY, x + width * 0.05F, groundY - height * 0.16F, x - width * 0.05F, groundY - height * 0.16F);
        for (int tier = 0; tier < 3; tier++) {
            float tierBottom = groundY - height * (0.12F + tier * 0.27F);
            float tierTop = tierBottom - height * (0.5F - tier * 0.08F);
            float half = width * (0.5F - tier * 0.12F);
            polygon(c, colors[0], x, tierTop, x - half, tierBottom, x, tierBottom);
            polygon(c, colors[1], x, tierTop, x, tierBottom, x + half, tierBottom);
            float sy = tierTop + (tierBottom - tierTop) * 0.28F;
            polygon(c, colors[3], x, tierTop, x - half * 0.3F, sy, x - half * 0.08F, sy - (sy - tierTop) * 0.2F, x + half * 0.12F, sy + (sy - tierTop) * 0.05F, x + half * 0.28F, sy);
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
