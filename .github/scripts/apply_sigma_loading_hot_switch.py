from pathlib import Path
import re
import textwrap


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one exact match in {path}, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def regex_once(path: str, pattern: str, replacement: str, label: str, flags: int = 0) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    result, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"{label}: expected one regex match in {path}, got {count}")
    p.write_text(result, encoding="utf-8")


# --- Original Sigma loading presentation on the modern 26.2 overlay lifecycle ---
loading_overlay = textwrap.dedent(r'''\
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
''')
Path("src/main/java/net/minecraft/client/gui/screens/LoadingOverlay.java").write_text(loading_overlay, encoding="utf-8")


# --- Minecraft account-bound service graph ---
mc = "src/main/java/net/minecraft/client/Minecraft.java"
replace_once(
    mc,
    "import com.mojang.realmsclient.RealmsMainScreen;\n",
    "import com.mojang.realmsclient.RealmsAvailability;\nimport com.mojang.realmsclient.RealmsMainScreen;\n",
    "RealmsAvailability import",
)
for old, new, label in [
    ("    private final CompletableFuture<@Nullable ProfileResult> profileFuture;", "    private CompletableFuture<@Nullable ProfileResult> profileFuture;", "mutable profile future"),
    ("    private final User user;", "    private User user;", "mutable user"),
    ("    private final UserApiService userApiService;", "    private UserApiService userApiService;", "mutable user api"),
    ("    private final CompletableFuture<UserProperties> userPropertiesFuture;", "    private CompletableFuture<UserProperties> userPropertiesFuture;", "mutable user properties"),
    ("    private final PlayerSocialManager playerSocialManager;", "    private PlayerSocialManager playerSocialManager;", "mutable social manager"),
    ("    private final RemoteFriendListUpdateHandler remoteFriendListUpdateHandler;", "    private RemoteFriendListUpdateHandler remoteFriendListUpdateHandler;", "mutable friends updater"),
    ("    private final ClientTelemetryManager telemetryManager;", "    private ClientTelemetryManager telemetryManager;", "mutable telemetry"),
    ("    private final ProfileKeyPairManager profileKeyPairManager;", "    private ProfileKeyPairManager profileKeyPairManager;", "mutable profile keys"),
    ("    private final RealmsDataFetcher realmsDataFetcher;", "    private RealmsDataFetcher realmsDataFetcher;", "mutable realms fetcher"),
]:
    replace_once(mc, old, new, label)
replace_once(
    mc,
    "    private final Services services;\n",
    "    private final Services services;\n    private boolean sigmaOfflineUser;\n",
    "Sigma offline flag",
)

replace_once(
    mc,
    "        this.user = gameConfig.user.user;\n        this.profileFuture = this.offlineDeveloperMode\n",
    "        this.user = gameConfig.user.user;\n        this.sigmaOfflineUser = !this.offlineDeveloperMode && \"0\".equals(this.user.getAccessToken());\n        boolean offlineUserServices = this.offlineDeveloperMode || this.sigmaOfflineUser;\n        this.profileFuture = offlineUserServices\n",
    "constructor offline identity",
)
replace_once(
    mc,
    "        this.userApiService = createUserApiService(authenticationService, gameConfig);\n",
    "        this.userApiService = createUserApiService(authenticationService, this.user, offlineUserServices);\n",
    "constructor user API",
)
regex_once(
    mc,
    r"        this\.userPropertiesFuture = CompletableFuture\.supplyAsync\(\(\) -> \{\n            try \{\n                return this\.userApiService\.fetchProperties\(\);\n            \} catch \(AuthenticationException e\) \{\n                LOGGER\.error\(\"Failed to fetch user properties\", e\);\n                return UserApiService\.OFFLINE_PROPERTIES;\n            \}\n        \}, Util\.nonCriticalIoPool\(\)\);",
    "        this.userPropertiesFuture = fetchUserProperties(this.userApiService, offlineUserServices);",
    "constructor user properties",
)
replace_once(
    mc,
    "        FriendsService friendsService = authenticationService.createFriendsService(this.user.getAccessToken());\n",
    "        YggdrasilAuthenticationService friendsAuthService = offlineUserServices\n            ? YggdrasilAuthenticationService.createOffline(this.proxy)\n            : authenticationService;\n        FriendsService friendsService = friendsAuthService.createFriendsService(this.user.getAccessToken());\n",
    "constructor friends service",
)
replace_once(
    mc,
    "        this.profileKeyPairManager = this.offlineDeveloperMode\n            ? ProfileKeyPairManager.EMPTY_KEY_MANAGER\n",
    "        this.profileKeyPairManager = offlineUserServices\n            ? ProfileKeyPairManager.EMPTY_KEY_MANAGER\n",
    "constructor profile keys",
)

replace_once(
    mc,
    "    private static UserApiService createUserApiService(final YggdrasilAuthenticationService authService, final GameConfig config) {\n        return config.game.offlineDeveloperMode ? UserApiService.OFFLINE : authService.createUserApiService(config.user.user.getAccessToken());\n    }\n",
    textwrap.dedent('''\
    private static UserApiService createUserApiService(
        final YggdrasilAuthenticationService authService,
        final User user,
        final boolean offline
    ) {
        return offline ? UserApiService.OFFLINE : authService.createUserApiService(user.getAccessToken());
    }

    private static CompletableFuture<UserProperties> fetchUserProperties(final UserApiService service, final boolean offline) {
        if (offline) {
            return CompletableFuture.completedFuture(UserApiService.OFFLINE_PROPERTIES);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return service.fetchProperties();
            } catch (AuthenticationException failure) {
                LOGGER.warn("Failed to fetch user properties", failure);
                return UserApiService.OFFLINE_PROPERTIES;
            }
        }, Util.nonCriticalIoPool());
    }
'''),
    "modern user API helpers",
)

replace_once(
    mc,
    "    public boolean isOfflineDeveloperMode() {\n        return this.offlineDeveloperMode;\n    }\n",
    textwrap.dedent('''\
    public boolean isOfflineDeveloperMode() {
        return this.offlineDeveloperMode;
    }

    /** Sigma offline accounts use local identity semantics even when the normal client is online-capable. */
    public boolean isSigmaOfflineUser() {
        return this.sigmaOfflineUser;
    }
'''),
    "Sigma offline getter",
)

# Insert the 26.2 hot switch directly after getUser().
replace_once(
    mc,
    "    public User getUser() {\n        return this.user;\n    }\n",
    textwrap.dedent('''\
    public User getUser() {
        return this.user;
    }

    /**
     * Switch the active identity without restarting. This is deliberately title-screen-only: changing the user while
     * a world, connection or integrated server is alive would mix two identities inside one network session.
     */
    public CompletableFuture<Boolean> sigmaSwitchUser(final User newUser, final boolean offlineAccount) {
        if (!this.sigmaCanSwitchUser()) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Util.nonCriticalIoPool().execute(() -> {
            try {
                boolean offlineServices = this.offlineDeveloperMode || offlineAccount;
                YggdrasilAuthenticationService authService = offlineServices
                    ? YggdrasilAuthenticationService.createOffline(this.proxy)
                    : new YggdrasilAuthenticationService(this.proxy);
                UserApiService userApi = createUserApiService(authService, newUser, offlineServices);
                UserProperties properties = offlineServices ? UserApiService.OFFLINE_PROPERTIES : fetchUserProperties(userApi, false).join();
                FriendsService friendsService = authService.createFriendsService(newUser.getAccessToken());
                ProfileResult profile = offlineServices ? null : this.services.sessionService().fetchProfile(newUser.getProfileId(), true);

                this.execute(() -> {
                    try {
                        if (!this.sigmaCanSwitchUser()) {
                            result.complete(false);
                            return;
                        }

                        if (this.remoteFriendListUpdateHandler != null) {
                            this.remoteFriendListUpdateHandler.close();
                        }
                        if (this.telemetryManager != null) {
                            this.telemetryManager.close();
                        }

                        this.user = newUser;
                        this.sigmaOfflineUser = offlineAccount;
                        this.profileFuture = CompletableFuture.completedFuture(profile);
                        this.userApiService = userApi;
                        this.userPropertiesFuture = CompletableFuture.completedFuture(properties);
                        this.remoteFriendListUpdateHandler = new RemoteFriendListUpdateHandler(friendsService, this);
                        this.playerSocialManager = new PlayerSocialManager(
                            this, this.userApiService, friendsService, this.remoteFriendListUpdateHandler
                        );
                        if (this.playerSocialManager.isFriendListEnabled()) {
                            this.remoteFriendListUpdateHandler.start();
                        }

                        this.telemetryManager = new ClientTelemetryManager(this, this.userApiService, this.user);
                        this.profileKeyPairManager = offlineServices
                            ? ProfileKeyPairManager.EMPTY_KEY_MANAGER
                            : ProfileKeyPairManager.create(this.userApiService, this.user, this.gameDirectory.toPath());
                        this.reportingContext = ReportingContext.create(ReportEnvironment.local(), this.userApiService);

                        RealmsAvailability.reset();
                        this.realmsDataFetcher = new RealmsDataFetcher(RealmsClient.getOrCreate(this));
                        this.updateTitle();
                        LOGGER.info("Sigma hot-switched user to {} ({})", this.user.getName(), this.user.getProfileId());
                        result.complete(true);
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private boolean sigmaCanSwitchUser() {
        return this.level == null
            && this.player == null
            && this.gameMode == null
            && this.pendingConnection == null
            && this.singleplayerServer == null
            && this.getConnection() == null;
    }
'''),
    "hot user switch",
)


# Server resource-pack identity must follow a hot-switched user rather than the constructor-captured UserData.
dps = "src/main/java/net/minecraft/client/resources/server/DownloadedPackSource.java"
replace_once(
    dps,
    "                WorldVersion version = SharedConstants.getCurrentVersion();\n                return Map.of(\n                    \"X-Minecraft-Username\",\n                    user.getName(),\n                    \"X-Minecraft-UUID\",\n                    UndashedUuid.toString(user.getProfileId()),\n",
    "                WorldVersion version = SharedConstants.getCurrentVersion();\n                User currentUser = DownloadedPackSource.this.minecraft.getUser();\n                return Map.of(\n                    \"X-Minecraft-Username\",\n                    currentUser.getName(),\n                    \"X-Minecraft-UUID\",\n                    UndashedUuid.toString(currentUser.getProfileId()),\n",
    "dynamic server pack identity",
)


# Realms caches identity in both its client singleton and availability future.
realms_client = "src/main/java/com/mojang/realmsclient/client/RealmsClient.java"
replace_once(
    realms_client,
    "        RealmsClient realmsClient = realmsClientInstance;\n        if (realmsClient != null) {\n            return realmsClient;\n        }\n\n        synchronized (RealmsClient.class) {\n            RealmsClient rc = realmsClientInstance;\n            if (rc != null) {\n                return rc;\n            }\n\n            rc = new RealmsClient(sessionId, username, minecraft);\n            realmsClientInstance = rc;\n            return rc;\n        }\n    }\n",
    "        RealmsClient realmsClient = realmsClientInstance;\n        if (realmsClient != null && realmsClient.matches(sessionId, username)) {\n            return realmsClient;\n        }\n\n        synchronized (RealmsClient.class) {\n            RealmsClient rc = realmsClientInstance;\n            if (rc == null || !rc.matches(sessionId, username)) {\n                rc = new RealmsClient(sessionId, username, minecraft);\n                realmsClientInstance = rc;\n            }\n            return rc;\n        }\n    }\n\n    private boolean matches(final String sessionId, final String username) {\n        return this.sessionId.equals(sessionId) && this.username.equals(username);\n    }\n",
    "identity-aware Realms client",
)
replace_once(
    realms_client,
    "        if (Minecraft.getInstance().isOfflineDeveloperMode()) {\n",
    "        if (Minecraft.getInstance().isOfflineDeveloperMode() || Minecraft.getInstance().isSigmaOfflineUser()) {\n",
    "Realms Sigma offline guard",
)

realms_availability = "src/main/java/com/mojang/realmsclient/RealmsAvailability.java"
replace_once(
    realms_availability,
    "    private static @Nullable CompletableFuture<RealmsAvailability.Result> future;\n",
    "    private static @Nullable CompletableFuture<RealmsAvailability.Result> future;\n\n    /** Drop cached availability whenever Sigma changes the active identity. */\n    public static synchronized void reset() {\n        future = null;\n    }\n",
    "Realms availability reset",
)
replace_once(
    realms_availability,
    "        if (Minecraft.getInstance().isOfflineDeveloperMode()) {\n",
    "        if (Minecraft.getInstance().isOfflineDeveloperMode() || Minecraft.getInstance().isSigmaOfflineUser()) {\n",
    "Realms availability Sigma offline guard",
)


# --- Sigma account store: refresh now, persist selection only after the hot switch succeeds ---
manager = "src/main/java/com/mentalfrostbyte/jello/account/SigmaAccountManager.java"
replace_once(
    manager,
    " * <p>A selected account is applied on the next launch. Minecraft 26.2 constructs authentication,\n * skin, social, profile-key and Realms services from the launch-time user, so replacing only the\n * visible session at runtime would leave a partially switched identity.</p>\n",
    " * <p>The selected identity is still persisted for the next launch, but the title-screen account manager can\n * also apply it immediately. Unlike Sigma 5's mutable Session fields, the 26.2 port rebuilds the account-bound\n * client services so the visible name, multiplayer authentication, social state and signing keys stay coherent.</p>\n",
    "account manager hot-switch docs",
)
replace_once(
    manager,
    "    public synchronized String selectedId() {\n        return this.selectedId;\n    }\n",
    textwrap.dedent('''\
    public synchronized String selectedId() {
        return this.selectedId;
    }

    /** Resolve/refresh credentials for a hot switch without committing the selection until the client accepts it. */
    public LaunchIdentity resolveForUse(final String id) throws Exception {
        AccountEntry account;
        synchronized (this) {
            account = this.find(id);
        }
        if (account == null) {
            throw new IllegalArgumentException("Unknown Sigma account: " + id);
        }

        LaunchIdentity identity = this.resolveIdentity(account);
        synchronized (this) {
            if (!this.accounts.contains(account)) {
                throw new IllegalStateException("Account was removed while credentials were refreshing");
            }
            // Persist refreshed OAuth state even before switching, but leave selectedId untouched on failure.
            this.save();
        }
        return identity;
    }
'''),
    "resolve account for hot use",
)
regex_once(
    manager,
    r"    private synchronized Optional<LaunchIdentity> resolveSelectedForLaunch\(\) \{.*?\n    \}\n\n    private AccountEntry find",
    textwrap.dedent('''\
    private Optional<LaunchIdentity> resolveSelectedForLaunch() {
        AccountEntry account;
        synchronized (this) {
            account = this.find(this.selectedId);
        }
        if (account == null) {
            return Optional.empty();
        }

        try {
            LaunchIdentity identity = this.resolveIdentity(account);
            synchronized (this) {
                account.lastUsed = System.currentTimeMillis();
                account.useCount++;
                this.save();
            }
            return Optional.of(identity);
        } catch (Exception failure) {
            LOGGER.error("Could not refresh selected Sigma account '{}'; falling back to launcher identity", account.name, failure);
            return Optional.empty();
        }
    }

    private LaunchIdentity resolveIdentity(final AccountEntry account) throws Exception {
        if (account.type == AccountType.OFFLINE) {
            return new LaunchIdentity(account.name, account.profileId, "0");
        }
        if (account.authState == null) {
            throw new IllegalStateException("Microsoft account has no OAuth state");
        }

        JavaAuthManager authManager = JavaAuthManager.fromJson(
            MinecraftAuth.createHttpClient(USER_AGENT), account.authState
        );
        MinecraftToken token = authManager.getMinecraftToken().getUpToDate();
        MinecraftProfile profile = authManager.getMinecraftProfile().getUpToDate();
        synchronized (this) {
            account.updateMicrosoft(profile, JavaAuthManager.toJson(authManager));
        }
        return new LaunchIdentity(profile.getName(), profile.getId(), token.getToken());
    }

    private AccountEntry find'''),
    "shared identity resolver",
    flags=re.S,
)


# --- Alt Manager UI: old Sigma behavior restored as Use Now, with 26.2-safe title-screen guard ---
screen = "src/main/java/com/mentalfrostbyte/jello/gui/account/SigmaAccountScreen.java"
replace_once(screen, "import java.util.Locale;\n", "import java.util.Locale;\nimport java.util.Optional;\nimport net.minecraft.client.User;\n", "hot switch imports")
replace_once(screen, "    private volatile boolean loginInProgress;\n", "    private volatile boolean loginInProgress;\n    private volatile boolean switchInProgress;\n", "switch progress flag")
replace_once(screen, 'Button.builder(Component.literal("Use Next Launch"), button -> this.useSelected())', 'Button.builder(Component.literal("Use Now"), button -> this.useSelected())', "Use Now button")
replace_once(screen, 'this.status = "Added Microsoft account " + account.getName() + ". Select Use Next Launch to activate it.";', 'this.status = "Added Microsoft account " + account.getName() + ". Select Use Now to activate it.";', "Microsoft activation message")
replace_once(
    screen,
    "        if (this.loginInProgress) {\n            return;\n        }\n\n        this.loginInProgress = true;\n",
    "        if (this.loginInProgress || this.switchInProgress) {\n            return;\n        }\n\n        this.loginInProgress = true;\n",
    "login/switch serialization",
)
regex_once(
    screen,
    r"    private void useSelected\(\) \{.*?\n    \}\n\n    private void useLauncherAccount",
    textwrap.dedent('''\
    private void useSelected() {
        AccountEntry account = this.selectedRow();
        if (account == null) {
            this.status = "Select an account first.";
            return;
        }
        if (this.switchInProgress) {
            return;
        }
        if (this.minecraft.level != null || this.minecraft.player != null || this.minecraft.getConnection() != null) {
            this.status = "Hot switching is only available from the title screen.";
            return;
        }

        this.switchInProgress = true;
        this.status = "Logging in...";
        this.updateControls();
        Thread thread = new Thread(() -> {
            try {
                SigmaAccountManager.LaunchIdentity identity = this.accounts.resolveForUse(account.getId());
                User user = new User(identity.name(), identity.profileId(), identity.accessToken(), Optional.empty(), Optional.empty());
                this.minecraft.sigmaSwitchUser(user, account.getType() == AccountType.OFFLINE).whenComplete((switched, failure) ->
                    this.minecraft.execute(() -> {
                        this.switchInProgress = false;
                        if (failure != null) {
                            Client.logger.error("Sigma hot account switch failed", failure);
                            this.status = "Login failed: " + concise(failure);
                        } else if (!switched) {
                            this.status = "Return to the title screen before switching accounts.";
                        } else {
                            this.accounts.selectForNextLaunch(account.getId());
                            this.status = "Logged in. (" + identity.name() + ")";
                        }
                        this.updateControls();
                    })
                );
            } catch (Throwable failure) {
                Client.logger.error("Sigma account refresh failed", failure);
                this.minecraft.execute(() -> {
                    this.switchInProgress = false;
                    this.status = "Login failed: " + concise(failure);
                    this.updateControls();
                });
            }
        }, "Sigma-Account-Switch");
        thread.setDaemon(true);
        thread.start();
    }

    private void useLauncherAccount'''),
    "Use Now hot switch",
    flags=re.S,
)
replace_once(
    screen,
    "    private void addOffline() {\n        try {\n",
    "    private void addOffline() {\n        if (this.switchInProgress) {\n            return;\n        }\n        try {\n",
    "offline add guard",
)
replace_once(
    screen,
    "    private void useLauncherAccount() {\n        this.accounts.useLauncherIdentity();\n",
    "    private void useLauncherAccount() {\n        if (this.switchInProgress) {\n            return;\n        }\n        this.accounts.useLauncherIdentity();\n",
    "launcher identity guard",
)
replace_once(
    screen,
    "    private void deleteSelected() {\n        AccountEntry account = this.selectedRow();\n",
    "    private void deleteSelected() {\n        if (this.switchInProgress) {\n            return;\n        }\n        AccountEntry account = this.selectedRow();\n",
    "delete guard",
)
replace_once(
    screen,
    "        if (this.useButton != null) {\n            this.useButton.active = this.selectedRow() != null;\n        }\n        if (this.deleteButton != null) {\n            this.deleteButton.active = this.selectedRow() != null;\n        }\n        if (this.microsoftButton != null) {\n            this.microsoftButton.active = !this.loginInProgress;\n        }\n",
    "        if (this.useButton != null) {\n            this.useButton.active = !this.switchInProgress && this.selectedRow() != null;\n        }\n        if (this.deleteButton != null) {\n            this.deleteButton.active = !this.switchInProgress && this.selectedRow() != null;\n        }\n        if (this.microsoftButton != null) {\n            this.microsoftButton.active = !this.loginInProgress && !this.switchInProgress;\n        }\n",
    "switch control locking",
)
replace_once(
    screen,
    "            if (account.getId().equals(nextId)) {\n                String next = \"NEXT\";\n                graphics.text(this.font, next, layout.listX + layout.listWidth - this.font.width(next) - 14, y + 7, TEXT_GOOD, false);\n            }\n",
    "            boolean active = account.getProfileId().equals(this.minecraft.getUser().getProfileId());\n            String marker = active ? \"ACTIVE\" : account.getId().equals(nextId) ? \"NEXT\" : null;\n            if (marker != null) {\n                graphics.text(this.font, marker, layout.listX + layout.listWidth - this.font.width(marker) - 14, y + 7, TEXT_GOOD, false);\n            }\n",
    "active account marker",
)

print("Sigma loading + hot-switch source patch applied")
