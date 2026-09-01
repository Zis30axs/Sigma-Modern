package net.minecraft.client.gui.screens;

import com.mentalfrostbyte.jello.util.client.render.LegacyUiScale;
import com.mentalfrostbyte.jello.util.client.render.theme.ClientColors;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Minecraft's resource-reload overlay with Sigma 5's original loading presentation. */
@OnlyIn(Dist.CLIENT)
public class LoadingOverlay extends Overlay {
    /** Retained for vanilla/source compatibility even though Sigma does not render the Mojang mark. */
    public static final Identifier MOJANG_STUDIOS_LOGO_LOCATION = Identifier.withDefaultNamespace("textures/gui/title/mojangstudios.png");
    private static final Identifier SIGMA_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/sigma/back.png");
    private static final Identifier SIGMA_LOGO = Identifier.withDefaultNamespace("textures/gui/sigma/logo.png");
    private static final int SIGMA_BACKGROUND_WIDTH = 1280;
    private static final int SIGMA_BACKGROUND_HEIGHT = 720;
    private static final int SIGMA_LOGO_TEXTURE_WIDTH = 910;
    private static final int SIGMA_LOGO_TEXTURE_HEIGHT = 156;
    private static final int LIGHT = ClientColors.LIGHT_GREYISH_BLUE.getColor();
    private static final int DEEP_TEAL = ClientColors.DEEP_TEAL.getColor();
    private static final float SMOOTHING = 0.95F;
    public static final long FADE_OUT_TIME = 1000L;
    public static final long FADE_IN_TIME = 500L;

    private final Minecraft minecraft;
    private final ReloadInstance reload;
    private final Consumer<Optional<Throwable>> onFinish;
    private final boolean fadeIn;
    private float currentProgress;
    private long fadeOutStart = -1L;
    private long fadeInStart = -1L;

    public LoadingOverlay(final Minecraft minecraft, final ReloadInstance reload, final Consumer<Optional<Throwable>> onFinish, final boolean fadeIn) {
        this.minecraft = minecraft;
        this.reload = reload;
        this.onFinish = onFinish;
        this.fadeIn = fadeIn;
    }

    /**
     * Initial resource loading happens before the normal texture reload is complete. These three textures therefore
     * load directly from the bundled classpath, preserving vanilla's bootstrap ordering.
     */
    public static void registerTextures(final TextureManager textureManager) {
        textureManager.registerAndLoad(MOJANG_STUDIOS_LOGO_LOCATION, new VanillaLogoTexture());
        textureManager.registerAndLoad(SIGMA_BACKGROUND, new SigmaBackgroundTexture());
        textureManager.registerAndLoad(SIGMA_LOGO, new BundledTexture(SIGMA_LOGO));
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        long now = Util.getMillis();
        if (this.fadeIn && this.fadeInStart == -1L) {
            this.fadeInStart = now;
        }

        float fadeOutAnim = this.fadeOutStart > -1L ? (float)(now - this.fadeOutStart) / 1000.0F : -1.0F;
        float fadeInAnim = this.fadeInStart > -1L ? (float)(now - this.fadeInStart) / 500.0F : -1.0F;
        float overlayAlpha;
        if (fadeOutAnim >= 1.0F) {
            if (this.minecraft.gui.screen() != null) {
                this.minecraft.gui.screen().extractRenderStateWithTooltipAndSubtitles(graphics, 0, 0, partialTick);
            } else {
                this.minecraft.gui.hud.extractDeferredSubtitles();
            }
            graphics.nextStratum();
            overlayAlpha = 1.0F - Mth.clamp(fadeOutAnim - 1.0F, 0.0F, 1.0F);
        } else if (this.fadeIn) {
            if (this.minecraft.gui.screen() != null && fadeInAnim < 1.0F) {
                this.minecraft.gui.screen().extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, partialTick);
            } else {
                this.minecraft.gui.hud.extractDeferredSubtitles();
            }
            graphics.nextStratum();
            overlayAlpha = Mth.clamp(fadeInAnim, 0.15F, 1.0F);
        } else {
            ARGB.setVector4fFromARGB32(this.minecraft.gameRenderer.gameRenderState().guiRenderState.clearColorOverride, 0xFF000000);
            overlayAlpha = 1.0F;
        }

        float actualProgress = this.reload.getActualProgress();
        this.currentProgress = Mth.clamp(this.currentProgress * SMOOTHING + actualProgress * (1.0F - SMOOTHING), 0.0F, 1.0F);
        this.extractSigmaLoading(graphics, width, height, overlayAlpha, fadeOutAnim < 1.0F);
        if (fadeOutAnim >= 2.0F) {
            this.minecraft.gui.setOverlay(null);
        }
    }

    /** Direct 26.2 equivalent of Sigma 5 LoadingScreen#xd; no legacy GL state is carried forward. */
    private void extractSigmaLoading(
        final GuiGraphicsExtractor graphics,
        final int width,
        final int height,
        final float alpha,
        final boolean drawProgress
    ) {
        int alphaByte = Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (alphaByte <= 0) {
            return;
        }

        int tint = ARGB.white(alphaByte / 255.0F);
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            SIGMA_BACKGROUND,
            0,
            0,
            0.0F,
            0.0F,
            width,
            height,
            SIGMA_BACKGROUND_WIDTH / 4,
            SIGMA_BACKGROUND_HEIGHT / 4,
            SIGMA_BACKGROUND_WIDTH / 4,
            SIGMA_BACKGROUND_HEIGHT / 4,
            tint
        );
        graphics.fill(0, 0, width, height, ARGB.color(Math.round(alphaByte * 0.75F), 0, 0, 0));

        int logoWidth = LegacyUiScale.size(455);
        int logoHeight = LegacyUiScale.size(78);
        float fit = Math.min(1.0F, Math.max(0.1F, (width - LegacyUiScale.size(20)) / (float)Math.max(1, logoWidth)));
        logoWidth = Math.max(1, Math.round(logoWidth * fit));
        logoHeight = Math.max(1, Math.round(logoHeight * fit));
        int logoX = (width - logoWidth) / 2;
        int logoY = Math.round((height - logoHeight) / 2.0F - LegacyUiScale.px(14.0F) * fit);

        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            SIGMA_LOGO,
            logoX,
            logoY,
            0.0F,
            0.0F,
            logoWidth,
            logoHeight,
            SIGMA_LOGO_TEXTURE_WIDTH,
            SIGMA_LOGO_TEXTURE_HEIGHT,
            SIGMA_LOGO_TEXTURE_WIDTH,
            SIGMA_LOGO_TEXTURE_HEIGHT,
            withAlpha(LIGHT, alphaByte)
        );

        if (!drawProgress) {
            return;
        }

        float progress = Math.min(1.0F, this.currentProgress * 1.02F);
        int barY = logoY + logoHeight + Math.round(LegacyUiScale.px(80.0F) * fit);
        int barHeight = Math.max(3, Math.round(LegacyUiScale.size(20) * fit));
        int border = Math.max(1, Math.round(LegacyUiScale.size(1) * fit));
        int inset = Math.max(border + 1, Math.round(LegacyUiScale.size(2) * fit));
        int radius = Math.max(1, Math.round(LegacyUiScale.size(10) * fit));

        fillRoundedRect(graphics, logoX, barY, logoWidth, barHeight, radius, withAlpha(LIGHT, Math.round(alphaByte * 0.30F)));
        fillRoundedRect(
            graphics,
            logoX + border,
            barY + border,
            Math.max(1, logoWidth - border * 2),
            Math.max(1, barHeight - border * 2),
            Math.max(1, radius - border),
            withAlpha(DEEP_TEAL, alphaByte)
        );

        int trackWidth = Math.max(0, logoWidth - inset * 2);
        int fillWidth = Math.round(trackWidth * progress);
        if (fillWidth > 0) {
            fillRoundedRect(
                graphics,
                logoX + inset,
                barY + inset,
                fillWidth,
                Math.max(1, barHeight - inset * 2),
                Math.max(1, radius - inset),
                withAlpha(LIGHT, Math.round(alphaByte * 0.90F))
            );
        }
    }

    private static int withAlpha(final int color, final int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | color & 0x00FFFFFF;
    }

    private static void fillRoundedRect(
        final GuiGraphicsExtractor graphics,
        final int x,
        final int y,
        final int width,
        final int height,
        final int requestedRadius,
        final int color
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int radius = Math.max(0, Math.min(requestedRadius, Math.min(width, height) / 2));
        if (radius == 0) {
            graphics.fill(x, y, x + width, y + height, color);
            return;
        }

        for (int row = 0; row < height; row++) {
            int distance = -1;
            if (row < radius) {
                distance = radius - row - 1;
            } else if (row >= height - radius) {
                distance = row - (height - radius);
            }
            int edgeInset = 0;
            if (distance >= 0) {
                double inside = Math.max(0.0, radius * (double)radius - distance * (double)distance);
                edgeInset = (int)Math.ceil(radius - Math.sqrt(inside));
            }
            graphics.fill(x + edgeInset, y + row, x + width - edgeInset, y + row + 1, color);
        }
    }

    @Override
    public void tick() {
        if (this.fadeOutStart == -1L && this.reload.isDone() && this.isReadyToFadeOut()) {
            try {
                this.reload.checkExceptions();
                this.onFinish.accept(Optional.empty());
            } catch (Throwable t) {
                this.onFinish.accept(Optional.of(t));
            }

            this.fadeOutStart = Util.getMillis();
            if (this.minecraft.gui.screen() != null) {
                Window window = this.minecraft.getWindow();
                this.minecraft.gui.screen().init(window.getGuiScaledWidth(), window.getGuiScaledHeight());
            }
        }
    }

    private boolean isReadyToFadeOut() {
        return !this.fadeIn || this.fadeInStart > -1L && Util.getMillis() - this.fadeInStart >= 1000L;
    }

    @OnlyIn(Dist.CLIENT)
    private static final class VanillaLogoTexture extends ReloadableTexture {
        private VanillaLogoTexture() {
            super(MOJANG_STUDIOS_LOGO_LOCATION);
        }

        @Override
        public TextureContents loadContents(final ResourceManager resourceManager) throws IOException {
            ResourceProvider vanillaProvider = Minecraft.getInstance().getVanillaPackResources().asProvider();
            try (InputStream resource = vanillaProvider.open(MOJANG_STUDIOS_LOGO_LOCATION)) {
                return plainContents(resource);
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static class BundledTexture extends ReloadableTexture {
        private final Identifier location;

        private BundledTexture(final Identifier location) {
            super(location);
            this.location = location;
        }

        @Override
        public TextureContents loadContents(final ResourceManager resourceManager) throws IOException {
            try (InputStream resource = openBundled(this.location)) {
                return plainContents(resource);
            }
        }
    }

    /** Reproduces Resources#createScaledAndProcessedTexture2(back, .25F, 25) from Sigma 5. */
    @OnlyIn(Dist.CLIENT)
    private static final class SigmaBackgroundTexture extends ReloadableTexture {
        private SigmaBackgroundTexture() {
            super(SIGMA_BACKGROUND);
        }

        @Override
        public TextureContents loadContents(final ResourceManager resourceManager) throws IOException {
            BufferedImage source;
            try (InputStream resource = openBundled(SIGMA_BACKGROUND)) {
                source = ImageIO.read(resource);
            }
            if (source == null) {
                throw new IOException("Could not decode Sigma loading background");
            }

            BufferedImage scaled = new BufferedImage(
                Math.max(1, (int)(source.getWidth() * 0.25F)),
                Math.max(1, (int)(source.getHeight() * 0.25F)),
                BufferedImage.TYPE_INT_ARGB
            );
            Graphics2D graphics = scaled.createGraphics();
            graphics.scale(0.25F, 0.25F);
            graphics.drawImage(source, 0, 0, null);
            graphics.dispose();

            BufferedImage processed = adjustImageHSB(applyBlur(addPadding(scaled, 25), 25), 0.0F, 1.1F, 0.0F);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(processed, "PNG", encoded)) {
                throw new IOException("Could not encode processed Sigma loading background");
            }
            try (ByteArrayInputStream input = new ByteArrayInputStream(encoded.toByteArray())) {
                return plainContents(input);
            }
        }
    }

    private static InputStream openBundled(final Identifier location) throws IOException {
        String classpath = "assets/" + location.getNamespace() + "/" + location.getPath();
        InputStream resource = LoadingOverlay.class.getClassLoader().getResourceAsStream(classpath);
        if (resource == null) {
            throw new IOException("Missing bundled Sigma loading resource: " + classpath);
        }
        return resource;
    }

    private static TextureContents plainContents(final InputStream resource) throws IOException {
        return new TextureContents(NativeImage.read(resource), new TextureMetadataSection(true, true, MipmapStrategy.MEAN, 0.0F));
    }

    private static BufferedImage addPadding(final BufferedImage source, final int amount) {
        int width = source.getWidth() + amount * 2;
        int height = source.getHeight() + amount * 2;
        BufferedImage padded = scale(source, (double)width / source.getWidth(), (double)height / source.getHeight());
        for (int x = 0; x < source.getWidth(); x++) {
            for (int y = 0; y < source.getHeight(); y++) {
                padded.setRGB(amount + x, amount + y, source.getRGB(x, y));
            }
        }
        return padded;
    }

    private static BufferedImage scale(final BufferedImage source, final double xScale, final double yScale) {
        int width = Math.max(1, (int)(source.getWidth() * xScale));
        int height = Math.max(1, (int)(source.getHeight() * yScale));
        BufferedImage result = new BufferedImage(width, height, source.getType());
        Graphics2D graphics = result.createGraphics();
        graphics.drawRenderedImage(source, AffineTransform.getScaleInstance(xScale, yScale));
        graphics.dispose();
        return result;
    }

    private static BufferedImage applyBlur(final BufferedImage image, final int amount) {
        if (image.getWidth() <= amount * 2 || image.getHeight() <= amount * 2) {
            return image;
        }
        ConvolveOp operation = new ConvolveOp(createGaussianKernel(amount));
        BufferedImage result = operation.filter(image, null);
        result = operation.filter(applyEdgeWrap(result), null);
        result = applyEdgeWrap(result);
        return result.getSubimage(amount, amount, image.getWidth() - amount * 2, image.getHeight() - amount * 2);
    }

    private static BufferedImage applyEdgeWrap(final BufferedImage input) {
        int width = input.getWidth();
        int height = input.getHeight();
        BufferedImage wrapped = new BufferedImage(height, width, input.getType());
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                wrapped.setRGB(height - 1 - y, width - 1 - x, input.getRGB(x, y));
            }
        }
        return wrapped;
    }

    private static Kernel createGaussianKernel(final float radius) {
        int kernelRadius = (int)Math.ceil(radius);
        int kernelSize = kernelRadius * 2 + 1;
        float[] data = new float[kernelSize];
        float standardDeviation = radius / 3.0F;
        float twoSigmaSquared = 2.0F * standardDeviation * standardDeviation;
        float normalizationFactor = (float)Math.sqrt(Math.PI * 2.0 * standardDeviation);
        float maxDistanceSquared = radius * radius;
        float sum = 0.0F;
        int index = 0;
        for (int offset = -kernelRadius; offset <= kernelRadius; offset++) {
            float distanceSquared = offset * offset;
            data[index] = distanceSquared <= maxDistanceSquared
                ? (float)Math.exp(-distanceSquared / twoSigmaSquared) / normalizationFactor
                : 0.0F;
            sum += data[index++];
        }
        for (int i = 0; i < data.length; i++) {
            data[i] /= sum;
        }
        return new Kernel(kernelSize, 1, data);
    }

    private static BufferedImage adjustImageHSB(
        final BufferedImage image,
        final float hueOffset,
        final float saturationMultiplier,
        final float brightnessOffset
    ) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                float[] hsb = Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
                float hue = Mth.clamp(hsb[0] + hueOffset, 0.0F, 1.0F);
                float saturation = Mth.clamp(hsb[1] * saturationMultiplier, 0.0F, 1.0F);
                float brightness = Mth.clamp(hsb[2] + brightnessOffset, 0.0F, 1.0F);
                image.setRGB(x, y, Color.HSBtoRGB(hue, saturation, brightness));
            }
        }
        return image;
    }
}
