package com.mentalfrostbyte.jello.gui.modern;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * Rounded liquid-glass floating HUD pill for BlockFly's remaining block counter.
 * Tints its frosted glass surface from the currently active/held block's color and
 * drives a smooth anti-aliased colored perimeter line scrolling around its outer edge.
 */
public final class ModernBlockFlyCounter {
    static final int DEFAULT_RGB = ModernStyle.ACCENT & 0x00FFFFFF;
    private static final int PILL_HEIGHT = 26;
    private static final int PILL_RADIUS = 13;
    private static final float MIN_WIDTH = 68.0F;
    private static final double BORDER_PERIOD_SECONDS = 2.2;
    private static final Identifier GRASS_TOP_SPRITE = Identifier.withDefaultNamespace("block/grass_block_top");

    private record ColorCacheKey(Block block, Identifier spriteId, int spriteIdentity, int tintRgb) {}

    private static final Map<ColorCacheKey, Integer> BLOCK_COLOR_CACHE = new LinkedHashMap<>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<ColorCacheKey, Integer> eldest) {
            return size() > 128;
        }
    };

    record Palette(
        int glassTintArgb,
        int scrollLineRgb,
        int scrollHeadRgb,
        int baseRimRgb,
        int topSheenArgb,
        int bottomCausticArgb,
        int haloRgb
    ) {}

    record BorderSample(int coreArgb, int glowArgb) {}

    record Contour(float[] px, float[] py, float[] nx, float[] ny, float[] u, int count) {
        static Contour build(final float x, final float y, final float w, final float h, final float radius) {
            float r = Math.clamp(radius, 0.0F, Math.min(w, h) * 0.5F);
            float straightW = Math.max(0.0F, w - 2.0F * r);
            float straightH = Math.max(0.0F, h - 2.0F * r);
            int topSteps = straightSteps(straightW);
            int rightSteps = straightSteps(straightH);
            int bottomSteps = topSteps;
            int leftSteps = rightSteps;
            int arcSteps = r > 1.0E-4F ? Math.max(10, (int) Math.ceil((Math.PI * 0.5 * r) / 1.6)) : 0;
            int total = topSteps + rightSteps + bottomSteps + leftSteps + arcSteps * 4;
            if (total < 4) total = 4;

            float[] px = new float[total];
            float[] py = new float[total];
            float[] nx = new float[total];
            float[] ny = new float[total];
            float[] u = new float[total + 1];
            int idx = 0;

            idx = addLine(px, py, nx, ny, idx, x + r, y, x + w - r, y, 0.0F, -1.0F, topSteps);
            idx = addArc(px, py, nx, ny, idx, x + w - r, y + r, r, -Math.PI * 0.5, 0.0, arcSteps);
            idx = addLine(px, py, nx, ny, idx, x + w, y + r, x + w, y + h - r, 1.0F, 0.0F, rightSteps);
            idx = addArc(px, py, nx, ny, idx, x + w - r, y + h - r, r, 0.0, Math.PI * 0.5, arcSteps);
            idx = addLine(px, py, nx, ny, idx, x + w - r, y + h, x + r, y + h, 0.0F, 1.0F, bottomSteps);
            idx = addArc(px, py, nx, ny, idx, x + r, y + h - r, r, Math.PI * 0.5, Math.PI, arcSteps);
            idx = addLine(px, py, nx, ny, idx, x, y + h - r, x, y + r, -1.0F, 0.0F, leftSteps);
            idx = addArc(px, py, nx, ny, idx, x + r, y + r, r, Math.PI, Math.PI * 1.5, arcSteps);

            float arcLength = 0.0F;
            for (int i = 1; i < idx; i++) {
                arcLength += (float) Math.hypot(px[i] - px[i - 1], py[i] - py[i - 1]);
                u[i] = arcLength;
            }
            if (idx > 0) {
                arcLength += (float) Math.hypot(px[0] - px[idx - 1], py[0] - py[idx - 1]);
            }
            if (arcLength > 1.0E-5F) {
                for (int i = 1; i < idx; i++) {
                    u[i] /= arcLength;
                }
                u[idx] = 1.0F;
            }
            return new Contour(px, py, nx, ny, u, idx);
        }

        private static int straightSteps(final float length) {
            return length > 1.0E-4F ? Math.max(1, (int) Math.ceil(length / 2.0F)) : 0;
        }

        private static int addLine(
            final float[] px, final float[] py, final float[] nx, final float[] ny,
            int idx, final float x0, final float y0, final float x1, final float y1,
            final float normX, final float normY, final int steps
        ) {
            for (int i = 0; i < steps; i++) {
                float t = i / (float) steps;
                px[idx] = x0 + (x1 - x0) * t;
                py[idx] = y0 + (y1 - y0) * t;
                nx[idx] = normX;
                ny[idx] = normY;
                idx++;
            }
            return idx;
        }

        private static int addArc(
            final float[] px, final float[] py, final float[] nx, final float[] ny,
            int idx, final float cx, final float cy, final float radius,
            final double startAngle, final double endAngle, final int steps
        ) {
            for (int i = 0; i < steps; i++) {
                double angle = startAngle + (endAngle - startAngle) * (i / (double) steps);
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);
                px[idx] = cx + cos * radius;
                py[idx] = cy + sin * radius;
                nx[idx] = cos;
                ny[idx] = sin;
                idx++;
            }
            return idx;
        }
    }

    @FunctionalInterface
    interface QuadConsumer {
        void accept(
            float x0, float y0, int c0,
            float x1, float y1, int c1,
            float x2, float y2, int c2,
            float x3, float y3, int c3
        );
    }

    private static long lastDrawNanos;
    private static long appearStartNanos;
    private static float smoothedWidth = -1.0F;
    private static float iconPresence = -1.0F;
    private static int shownRgb = DEFAULT_RGB;
    private static @Nullable ItemStack lastKnownStack;

    private ModernBlockFlyCounter() {}

    /** Resets transient HUD animation state when BlockFly disables or leaves a world. */
    public static void reset() {
        lastDrawNanos = 0L;
        appearStartNanos = 0L;
        smoothedWidth = -1.0F;
        iconPresence = -1.0F;
        shownRgb = DEFAULT_RGB;
        lastKnownStack = null;
    }

    /** Renders the centered liquid-glass floating block counter pill at {@code (centerX, topY)}. */
    public static void render(
        final GuiGraphicsExtractor g,
        final int count,
        final @Nullable ItemStack activeStack,
        final float centerX,
        final float topY
    ) {
        if (g == null) return;
        long now = System.nanoTime();
        boolean gap = lastDrawNanos == 0L || now - lastDrawNanos > 250_000_000L;
        if (gap) appearStartNanos = now;
        float dt = gap ? 0.0F : Math.min(0.05F, (now - lastDrawNanos) / 1_000_000_000.0F);
        lastDrawNanos = now;

        float appear = ModernStyle.easeOut((now - appearStartNanos) / 1_000_000_000.0F / 0.26F);
        float scale = appear >= 0.999F ? 1.0F : 0.92F + 0.08F * appear;

        boolean hasActive = activeStack != null && !activeStack.isEmpty();
        if (hasActive) lastKnownStack = activeStack.copyWithCount(1);

        int targetRgb = hasActive ? resolveBlockColor(activeStack) : DEFAULT_RGB;
        if (gap) {
            iconPresence = hasActive ? 1.0F : 0.0F;
            shownRgb = targetRgb;
        } else {
            iconPresence = ModernStyle.smooth(iconPresence, hasActive ? 1.0F : 0.0F, dt, 16.0F);
            float colorBlend = 1.0F - (float) Math.exp(-dt * 12.0F);
            shownRgb = ModernStyle.mix(0xFF000000 | shownRgb, 0xFF000000 | targetRgb, colorBlend) & 0x00FFFFFF;
        }
        if (!hasActive && iconPresence <= 0.02F) lastKnownStack = null;

        int shownCount = Math.max(0, count);
        String numberText = Integer.toString(shownCount);
        String labelText = shownCount == 1 ? " Block" : " Blocks";
        float numberWidth = ModernTypography.width(ModernTypography.Face.TEXT, numberText, 1.0F);
        float labelWidth = ModernTypography.width(ModernTypography.Face.TEXT, labelText, 0.84F);
        float textWidth = numberWidth + labelWidth;

        float leftPad = 5.0F + (1.0F - iconPresence) * 5.0F;
        float iconSpan = iconPresence * 23.0F;
        float targetWidth = Math.max(MIN_WIDTH, leftPad + iconSpan + textWidth + 11.0F);
        smoothedWidth = smoothedWidth < 0.0F || gap ? targetWidth : ModernStyle.smooth(smoothedWidth, targetWidth, dt, 16.0F);

        int w = Math.max(Math.round(MIN_WIDTH), Math.round(smoothedWidth));
        int h = PILL_HEIGHT;
        int x = Math.round(centerX - w * 0.5F);
        int y = Math.round(topY);
        Palette palette = paletteFromRgb(shownRgb);
        float phase = positiveMod((float) ((now / 1_000_000_000.0) / BORDER_PERIOD_SECONDS), 1.0F);
        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int viewportW = g.guiWidth();
        int viewportH = g.guiHeight();

        g.nextStratum();
        g.pose().pushMatrix();
        try (var fade = ModernStyle.alphaScope(appear)) {
            float pivotX = x + w * 0.5F;
            float pivotY = y + h * 0.5F;
            g.pose().translate(pivotX, pivotY + (1.0F - appear) * 4.0F);
            g.pose().scale(scale, scale);
            g.pose().translate(-pivotX, -pivotY);

            ModernStyle.dropShadow(g, x, y + 3, w, h, PILL_RADIUS, 0.82F);
            ModernStyle.halo(g, x, y, w, h, PILL_RADIUS, palette.haloRgb(), 0.28F);
            g.sigma$blurRounded(x, y, w, h, PILL_RADIUS, 0.90F);
            ModernStyle.rounded(g, x, y, w, h, PILL_RADIUS, palette.glassTintArgb());
            ModernStyle.roundedGradient(g, x + 1, y + 1, w - 2, h - 2, PILL_RADIUS - 1,
                    y + 1, y + h / 2 + 1, palette.topSheenArgb(), 0x00FFFFFF);
            ModernStyle.roundedGradient(g, x + 1, y + 1, w - 2, h - 2, PILL_RADIUS - 1,
                    y + h - 8, y + h - 1, 0x00000000 | palette.scrollLineRgb(), palette.bottomCausticArgb());

            g.enableScissor(x, y, x + w, y + h);
            try {
                g.pose().pushMatrix();
                try {
                    g.pose().translate(x + w * 0.24F, y + 1.5F);
                    g.pose().scale(1.0F, 0.52F);
                    ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, -32.0F, -32.0F, 64.0F, 0x28FFFFFF);
                } finally {
                    g.pose().popMatrix();
                }
            } finally {
                g.disableScissor();
            }

            float groupAlpha = ModernStyle.groupAlpha();
            Contour contour = Contour.build(x, y, w, h, PILL_RADIUS);
            g.sigma$submitElement((pose, scissor) -> ScrollingBorderElement.create(
                    pose, scissor, contour, phase, palette, groupAlpha, guiScale, x, y, w, h, viewportW, viewportH));

            if (iconPresence > 0.02F && lastKnownStack != null && !lastKnownStack.isEmpty()) {
                try (var iconFade = ModernStyle.alphaScope(iconPresence)) {
                    ModernStyle.rounded(g, x + 5, y + 4, 18, 18, 6, 0x2C000000 | palette.scrollLineRgb());
                    ModernStyle.rounded(g, x + 5, y + 4, 18, 18, 6, 0x16FFFFFF);
                }
                if (iconPresence >= 0.15F) {
                    g.fakeItem(lastKnownStack, x + 6, y + 5);
                }
            }

            float textX = x + leftPad + iconSpan;
            float numberY = y + 7.5F;
            float labelY = numberY + 9.0F * (1.0F - 0.84F);
            int countColor = 0xFFF6FBFF;
            int labelColor = ModernStyle.mix(0xFFE4F0F8, 0xFF000000 | palette.scrollHeadRgb(), 0.24F);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, numberText, textX, numberY, 1.0F, countColor);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, labelText, textX + numberWidth, labelY, 0.84F, labelColor);
        } finally {
            g.pose().popMatrix();
        }
        g.nextStratum();
    }

    /** Extracts a representative RGB color for a block stack, caching by block/sprite/tint. */
    static int resolveBlockColor(final @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return DEFAULT_RGB;
        }
        Block block = blockItem.getBlock();
        BlockState state = block.defaultBlockState();
        Minecraft mc = Minecraft.getInstance();

        int tintRgb = resolveTintRgb(mc, state);
        Identifier spriteId = resolveSpriteId(mc, block, state);
        int spriteIdentity = spriteId == null ? 0 : spriteId.hashCode();

        ColorCacheKey key = new ColorCacheKey(block, spriteId != null ? spriteId : Identifier.withDefaultNamespace("missing"), spriteIdentity, tintRgb);
        Integer cached = BLOCK_COLOR_CACHE.get(key);
        if (cached != null) return cached;

        Integer sampled = spriteId != null ? loadAndSampleSprite(mc, spriteId, tintRgb) : null;
        int resolved = sampled != null ? sampled : fallbackBlockColor(state);
        BLOCK_COLOR_CACHE.put(key, resolved);
        return resolved;
    }

    private static int resolveTintRgb(final Minecraft mc, final BlockState state) {
        try {
            BlockTintSource tintSource = mc.getBlockColors().getTintSource(state, 0);
            if (tintSource == null) return 0xFFFFFF;
            int color = mc.level != null && mc.player != null
                    ? tintSource.colorInWorld(state, mc.level, mc.player.blockPosition())
                    : tintSource.color(state);
            return color & 0x00FFFFFF;
        } catch (RuntimeException ignored) {
            return 0xFFFFFF;
        }
    }

    private static @Nullable Identifier resolveSpriteId(final Minecraft mc, final Block block, final BlockState state) {
        if (block == Blocks.GRASS_BLOCK) {
            return GRASS_TOP_SPRITE;
        }
        try {
            TextureAtlasSprite sprite = mc.getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
            return sprite == null ? null : sprite.contents().name();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static @Nullable Integer loadAndSampleSprite(final Minecraft mc, final Identifier spriteId, final int tintRgb) {
        Identifier textureLocation = Identifier.fromNamespaceAndPath(spriteId.getNamespace(), "textures/" + spriteId.getPath() + ".png");
        Optional<Resource> resource = mc.getResourceManager().getResource(textureLocation);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open()) {
            BufferedImage image = ImageIO.read(in);
            return sampleImage(image, tintRgb);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int fallbackBlockColor(final BlockState state) {
        int mapRgb = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col & 0x00FFFFFF;
        return mapRgb == 0 ? DEFAULT_RGB : mapRgb;
    }

    /** Samples up to a 16x16 grid of frame 0 of a block sprite, multiplying by {@code tintRgb}. */
    static @Nullable Integer sampleImage(final @Nullable BufferedImage image, final int tintRgb) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return null;
        int frameW = image.getWidth();
        int frameH = Math.min(image.getHeight(), frameW);
        int samplesX = Math.min(16, frameW);
        int samplesY = Math.min(16, frameH);
        int tintR = (tintRgb >> 16) & 0xFF;
        int tintG = (tintRgb >> 8) & 0xFF;
        int tintB = tintRgb & 0xFF;

        double totalAlphaWeight = 0.0;
        double avgR = 0.0, avgG = 0.0, avgB = 0.0;
        double vividWeight = 0.0;
        double vividR = 0.0, vividG = 0.0, vividB = 0.0;
        float[] hsb = new float[3];

        for (int sy = 0; sy < samplesY; sy++) {
            int py = (sy * frameH) / samplesY + Math.max(0, frameH / (samplesY * 2));
            py = Math.min(frameH - 1, py);
            for (int sx = 0; sx < samplesX; sx++) {
                int px = (sx * frameW) / samplesX + Math.max(0, frameW / (samplesX * 2));
                px = Math.min(frameW - 1, px);
                int argb = image.getRGB(px, py);
                int a = (argb >>> 24) & 0xFF;
                if (a < 64) continue;

                int r = (((argb >> 16) & 0xFF) * tintR) / 255;
                int g = (((argb >> 8) & 0xFF) * tintG) / 255;
                int b = ((argb & 0xFF) * tintB) / 255;

                double alphaW = a / 255.0;
                totalAlphaWeight += alphaW;
                avgR += r * alphaW;
                avgG += g * alphaW;
                avgB += b * alphaW;

                Color.RGBtoHSB(r, g, b, hsb);
                if (hsb[1] >= 0.18F && hsb[2] >= 0.14F) {
                    double vw = alphaW * hsb[1] * (0.35 + 0.65 * hsb[2]);
                    vividWeight += vw;
                    vividR += r * vw;
                    vividG += g * vw;
                    vividB += b * vw;
                }
            }
        }

        if (totalAlphaWeight <= 1.0E-4) return null;
        int baseRgb = packRgb((int) Math.round(avgR / totalAlphaWeight),
                (int) Math.round(avgG / totalAlphaWeight),
                (int) Math.round(avgB / totalAlphaWeight));
        if (vividWeight <= 1.0E-4) return baseRgb;

        int peakRgb = packRgb((int) Math.round(vividR / vividWeight),
                (int) Math.round(vividG / vividWeight),
                (int) Math.round(vividB / vividWeight));
        float vividMix = Math.clamp((float) (vividWeight / totalAlphaWeight * 1.8), 0.0F, 0.78F);
        return ModernStyle.mix(0xFF000000 | baseRgb, 0xFF000000 | peakRgb, vividMix) & 0x00FFFFFF;
    }

    /** Derives readable translucent glass fills and a bright scrolling border line from a raw block RGB. */
    static Palette paletteFromRgb(final int rawRgb) {
        int r = (rawRgb >> 16) & 0xFF;
        int g = (rawRgb >> 8) & 0xFF;
        int b = rawRgb & 0xFF;
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        float hue = hsb[0];
        float sat = hsb[1];
        float bri = hsb[2];

        int glassTintArgb;
        if (sat < 0.14F && bri > 0.72F) {
            int frosted = ModernStyle.mix(0xFF26323E, 0xFFFFFFFF, 0.28F) & 0x00FFFFFF;
            glassTintArgb = 0x6A000000 | frosted;
        } else {
            int toned = Color.HSBtoRGB(hue, Math.min(0.75F, sat * 0.90F), Math.clamp(bri * 0.46F + 0.10F, 0.11F, 0.42F));
            int mixed = ModernStyle.mix(0xFF0C1722, toned, 0.75F) & 0x00FFFFFF;
            glassTintArgb = 0x6C000000 | mixed;
        }

        int scrollLineRgb;
        if (sat >= 0.15F) {
            scrollLineRgb = Color.HSBtoRGB(hue, Math.clamp(sat * 1.12F, 0.38F, 0.95F),
                    Math.clamp(Math.max(bri * 1.18F, 0.76F), 0.76F, 1.0F)) & 0x00FFFFFF;
        } else if (bri >= 0.72F) {
            scrollLineRgb = 0xF4F9FF;
        } else if (bri >= 0.30F) {
            scrollLineRgb = 0xCBD5E1;
        } else {
            scrollLineRgb = 0x94A3B8;
        }

        int scrollHeadRgb = ModernStyle.mix(0xFF000000 | scrollLineRgb, 0xFFFFFFFF, 0.45F) & 0x00FFFFFF;
        int baseRimRgb = ModernStyle.mix(0xFF000000 | scrollLineRgb, 0xFFFFFFFF, 0.35F) & 0x00FFFFFF;
        int topSheenArgb = 0x32000000 | (ModernStyle.mix(0xFF000000 | scrollLineRgb, 0xFFFFFFFF, 0.65F) & 0x00FFFFFF);
        int bottomCausticArgb = 0x22000000 | scrollLineRgb;
        return new Palette(glassTintArgb, scrollLineRgb, scrollHeadRgb, baseRimRgb, topSheenArgb, bottomCausticArgb, scrollLineRgb);
    }

    /** Evaluates two symmetric scrolling light strokes traveling clockwise along normalized arc-length {@code u}. */
    static BorderSample sampleBorder(final float u, final float phase, final Palette palette, final float groupAlpha) {
        float rel = positiveMod(phase - u, 0.5F) * 2.0F;
        float wave;
        float head;
        if (rel >= 0.94F) {
            float t = (rel - 0.94F) / 0.06F;
            wave = t * t * (3.0F - 2.0F * t);
            head = wave;
        } else if (rel <= 0.44F) {
            float tail = 1.0F - rel / 0.44F;
            wave = tail * tail * (0.65F + 0.35F * tail);
            head = rel < 0.10F ? 1.0F - rel / 0.10F : 0.0F;
        } else {
            wave = 0.0F;
            head = 0.0F;
        }

        float baseAlpha = 0.24F;
        float coreAlpha = Math.clamp(baseAlpha + (0.96F - baseAlpha) * wave, 0.0F, 1.0F) * groupAlpha;
        float glowAlpha = Math.clamp(0.34F * wave * wave, 0.0F, 1.0F) * groupAlpha;

        int strokeColor = ModernStyle.mix(0xFF000000 | palette.scrollLineRgb(), 0xFF000000 | palette.scrollHeadRgb(), head);
        int coreRgb = ModernStyle.mix(0xFF000000 | palette.baseRimRgb(), strokeColor, Math.clamp(wave * 1.3F, 0.0F, 1.0F)) & 0x00FFFFFF;
        int coreArgb = Math.clamp(Math.round(coreAlpha * 255.0F), 0, 255) << 24 | coreRgb;
        int glowArgb = Math.clamp(Math.round(glowAlpha * 255.0F), 0, 255) << 24 | palette.scrollLineRgb();
        return new BorderSample(coreArgb, glowArgb);
    }

    static void emitVertices(
        final Contour contour,
        final Matrix3x2fc pose,
        final float phase,
        final Palette palette,
        final float groupAlpha,
        final int guiScale,
        final QuadConsumer sink
    ) {
        int count = contour.count;
        if (count < 3 || groupAlpha <= 0.002F) return;

        float m00 = pose.m00(), m01 = pose.m01(), m10 = pose.m10(), m11 = pose.m11(), m20 = pose.m20(), m21 = pose.m21();
        float poseScale = Math.max((float) Math.hypot(m00, m01), (float) Math.hypot(m10, m11));
        float aa = Math.clamp(0.75F / Math.max(0.001F, poseScale * Math.max(1, guiScale)), 0.25F, 1.0F);
        float centerOffset = -0.7F;
        float halfStroke = 0.8F;
        float dInEdge = centerOffset - halfStroke - aa;
        float dInCore = centerOffset - halfStroke;
        float dOutCore = centerOffset + halfStroke;
        float dOutFeather = centerOffset + halfStroke + aa;
        float dGlowOuter = dOutFeather + 2.2F;

        BorderSample[] samples = new BorderSample[count + 1];
        for (int i = 0; i <= count; i++) {
            samples[i] = sampleBorder(contour.u[i], phase, palette, groupAlpha);
        }

        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            BorderSample s0 = samples[i];
            BorderSample s1 = samples[i + 1];
            int clearCore0 = s0.coreArgb() & 0x00FFFFFF;
            int clearCore1 = s1.coreArgb() & 0x00FFFFFF;
            int clearGlow0 = s0.glowArgb() & 0x00FFFFFF;
            int clearGlow1 = s1.glowArgb() & 0x00FFFFFF;

            float x0 = contour.px[i], y0 = contour.py[i], nx0 = contour.nx[i], ny0 = contour.ny[i];
            float x1 = contour.px[next], y1 = contour.py[next], nx1 = contour.nx[next], ny1 = contour.ny[next];

            emitStrip(m00, m01, m10, m11, m20, m21, x0, y0, nx0, ny0, x1, y1, nx1, ny1,
                    dInEdge, dInCore, clearCore0, s0.coreArgb(), clearCore1, s1.coreArgb(), sink);
            emitStrip(m00, m01, m10, m11, m20, m21, x0, y0, nx0, ny0, x1, y1, nx1, ny1,
                    dInCore, dOutCore, s0.coreArgb(), s0.coreArgb(), s1.coreArgb(), s1.coreArgb(), sink);
            emitStrip(m00, m01, m10, m11, m20, m21, x0, y0, nx0, ny0, x1, y1, nx1, ny1,
                    dOutCore, dOutFeather, s0.coreArgb(), s0.glowArgb(), s1.coreArgb(), s1.glowArgb(), sink);

            if (((s0.glowArgb() >>> 24) > 1) || ((s1.glowArgb() >>> 24) > 1)) {
                emitStrip(m00, m01, m10, m11, m20, m21, x0, y0, nx0, ny0, x1, y1, nx1, ny1,
                        dOutFeather, dGlowOuter, s0.glowArgb(), clearGlow0, s1.glowArgb(), clearGlow1, sink);
            }
        }
    }

    private static void emitStrip(
        final float m00, final float m01, final float m10, final float m11, final float m20, final float m21,
        final float x0, final float y0, final float nx0, final float ny0,
        final float x1, final float y1, final float nx1, final float ny1,
        final float dInner, final float dOuter,
        final int innerColor0, final int outerColor0,
        final int innerColor1, final int outerColor1,
        final QuadConsumer sink
    ) {
        float lxOut0 = x0 + nx0 * dOuter, lyOut0 = y0 + ny0 * dOuter;
        float lxIn0 = x0 + nx0 * dInner, lyIn0 = y0 + ny0 * dInner;
        float lxIn1 = x1 + nx1 * dInner, lyIn1 = y1 + ny1 * dInner;
        float lxOut1 = x1 + nx1 * dOuter, lyOut1 = y1 + ny1 * dOuter;

        sink.accept(
            m00 * lxOut0 + m10 * lyOut0 + m20, m01 * lxOut0 + m11 * lyOut0 + m21, outerColor0,
            m00 * lxIn0 + m10 * lyIn0 + m20, m01 * lxIn0 + m11 * lyIn0 + m21, innerColor0,
            m00 * lxIn1 + m10 * lyIn1 + m20, m01 * lxIn1 + m11 * lyIn1 + m21, innerColor1,
            m00 * lxOut1 + m10 * lyOut1 + m20, m01 * lxOut1 + m11 * lyOut1 + m21, outerColor1
        );
    }

    private static float positiveMod(final float value, final float period) {
        float mod = value % period;
        return mod < 0.0F ? mod + period : mod;
    }

    private static int packRgb(final int r, final int g, final int b) {
        return Math.clamp(r, 0, 255) << 16 | Math.clamp(g, 0, 255) << 8 | Math.clamp(b, 0, 255);
    }

    private static final class ScrollingBorderElement implements GuiElementRenderState {
        private final Matrix3x2f pose;
        private final @Nullable ScreenRectangle scissorArea;
        private final ScreenRectangle bounds;
        private final Contour contour;
        private final float phase;
        private final Palette palette;
        private final float groupAlpha;
        private final int guiScale;

        private ScrollingBorderElement(
            final Matrix3x2f pose,
            final @Nullable ScreenRectangle scissorArea,
            final ScreenRectangle bounds,
            final Contour contour,
            final float phase,
            final Palette palette,
            final float groupAlpha,
            final int guiScale
        ) {
            this.pose = pose;
            this.scissorArea = scissorArea;
            this.bounds = bounds;
            this.contour = contour;
            this.phase = phase;
            this.palette = palette;
            this.groupAlpha = groupAlpha;
            this.guiScale = guiScale;
        }

        static @Nullable ScrollingBorderElement create(
            final Matrix3x2f pose,
            final @Nullable ScreenRectangle scissorArea,
            final Contour contour,
            final float phase,
            final Palette palette,
            final float groupAlpha,
            final int guiScale,
            final int x,
            final int y,
            final int w,
            final int h,
            final int viewportW,
            final int viewportH
        ) {
            if (viewportW <= 0 || viewportH <= 0 || groupAlpha <= 0.002F) return null;
            ScreenRectangle viewport = new ScreenRectangle(0, 0, viewportW, viewportH);
            ScreenRectangle clip = scissorArea == null ? viewport : scissorArea.intersection(viewport);
            if (clip == null) return null;
            ScreenRectangle bounds = new ScreenRectangle(x - 5, y - 5, w + 10, h + 10).transformMaxBounds(pose).intersection(clip);
            if (bounds == null) return null;
            return new ScrollingBorderElement(pose, scissorArea, bounds, contour, phase, palette, groupAlpha, guiScale);
        }

        @Override public RenderPipeline pipeline() { return RenderPipelines.GUI; }
        @Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }
        @Override public @Nullable ScreenRectangle scissorArea() { return this.scissorArea; }
        @Override public ScreenRectangle bounds() { return this.bounds; }

        @Override
        public void buildVertices(final VertexConsumer buffer) {
            emitVertices(this.contour, this.pose, this.phase, this.palette, this.groupAlpha, this.guiScale,
                (x0, y0, c0, x1, y1, c1, x2, y2, c2, x3, y3, c3) -> {
                    buffer.addVertex(x0, y0, 0.0F).setColor(c0);
                    buffer.addVertex(x1, y1, 0.0F).setColor(c1);
                    buffer.addVertex(x2, y2, 0.0F).setColor(c2);
                    buffer.addVertex(x3, y3, 0.0F).setColor(c3);
                });
        }
    }
}
