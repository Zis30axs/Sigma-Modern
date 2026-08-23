package net.minecraft.client.renderer.texture;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map.Entry;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
// MODIFIED for porting: implements sodium's TextureAtlasAccessor (mixin.core.render.texture.TextureAtlasAccessor)
public class TextureAtlas extends AbstractTexture implements TickableTexture, Dumpable,
    net.caffeinemc.mods.sodium.mixin.core.render.texture.TextureAtlasAccessor,
    net.caffeinemc.mods.sodium.client.render.texture.ExtendedTextureAtlas,
    net.irisshaders.iris.mixin.texture.TextureAtlasAccessor,
    net.irisshaders.iris.pbr.texture.TextureAtlasExtension { // MODIFIED for porting: sodium core.render TextureAtlasMixin, iris texture TextureAtlasAccessor + pbr TextureAtlasExtension
    private static final Logger LOGGER = LogUtils.getLogger();
    @Deprecated
    public static final Identifier LOCATION_BLOCKS = Identifier.withDefaultNamespace("textures/atlas/blocks.png");
    @Deprecated
    public static final Identifier LOCATION_ITEMS = Identifier.withDefaultNamespace("textures/atlas/items.png");
    @Deprecated
    public static final Identifier LOCATION_PARTICLES = Identifier.withDefaultNamespace("textures/atlas/particles.png");
    private List<TextureAtlasSprite> sprites = List.of();
    private List<SpriteContents.AnimationState> animatedTexturesStates = List.of();
    private Map<Identifier, TextureAtlasSprite> texturesByName = Map.of();
    // MODIFIED for porting: iris texture pbr MixinTextureAtlas @Unique field (its pbr TextureAtlasExtension implementation)
    private net.irisshaders.iris.pbr.texture.PBRAtlasHolder iris$pbrHolder;

    @Override
    public net.irisshaders.iris.pbr.texture.PBRAtlasHolder getPBRHolder() {
        return this.iris$pbrHolder;
    }

    @Override
    public net.irisshaders.iris.pbr.texture.PBRAtlasHolder getOrCreatePBRHolder() {
        if (this.iris$pbrHolder == null) {
            this.iris$pbrHolder = new net.irisshaders.iris.pbr.texture.PBRAtlasHolder();
        }

        return this.iris$pbrHolder;
    }

    // MODIFIED for porting: was iris's texture TextureAtlasAccessor (@Accessor("texturesByName"),
    // @Accessor("maxMipLevel"), @Invoker("getWidth"), @Invoker("getHeight"))
    @Override
    public Map<Identifier, TextureAtlasSprite> getTexturesByName() {
        return this.texturesByName;
    }

    @Override
    public int getMaxLevel() {
        return this.maxMipLevel;
    }

    @Override
    public int callGetWidth() {
        return this.getWidth();
    }

    @Override
    public int callGetHeight() {
        return this.getHeight();
    }
    private @Nullable TextureAtlasSprite missingSprite;
    private final Identifier location;
    private final int maxSupportedTextureSize;
    private int width;
    private int height;
    private int maxMipLevel;
    private int mipLevelCount;
    private GpuTextureView[] mipViews = new GpuTextureView[0];
    private @Nullable GpuBuffer spriteUbos;

    public TextureAtlas(final Identifier location) {
        this.location = location;
        this.maxSupportedTextureSize = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
    }

    private void createTexture(final int newWidth, final int newHeight, final int newMipLevel) {
        LOGGER.info("Created: {}x{}x{} {}-atlas", newWidth, newHeight, newMipLevel, this.location);
        GpuDevice device = RenderSystem.getDevice();
        this.releaseTextures();
        this.texture = device.createTexture(this.location::toString, 15, GpuFormat.RGBA8_UNORM, newWidth, newHeight, 1, newMipLevel + 1);
        this.textureView = device.createTextureView(this.texture);
        this.width = newWidth;
        this.height = newHeight;
        this.maxMipLevel = newMipLevel;
        this.mipLevelCount = newMipLevel + 1;
        this.mipViews = new GpuTextureView[this.mipLevelCount];

        for (int level = 0; level <= this.maxMipLevel; level++) {
            this.mipViews[level] = device.createTextureView(this.texture, level, 1);
        }
    }

    public void upload(final SpriteLoader.Preparations preparations) {
        this.createTexture(preparations.width(), preparations.height(), preparations.mipLevel());
        this.clearTextureData();
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        this.texturesByName = Map.copyOf(preparations.regions());
        this.missingSprite = this.texturesByName.get(MissingTextureAtlasSprite.getLocation());
        if (this.missingSprite == null) {
            throw new IllegalStateException("Atlas '" + this.location + "' (" + this.texturesByName.size() + " sprites) has no missing texture sprite");
        }

        Builder<TextureAtlasSprite> spritesBuilder = ImmutableList.builder();
        int animatedSpriteCount = 0;

        for (TextureAtlasSprite sprite : preparations.regions().values()) {
            spritesBuilder.add(sprite);
            if (sprite.isAnimated()) {
                animatedSpriteCount++;
            }
        }

        this.sprites = spritesBuilder.build();
        if (animatedSpriteCount > 0) {
            Builder<SpriteContents.AnimationState> animationStates = ImmutableList.builder();
            int spriteUboSize = Mth.roundToward(SpriteContents.UBO_SIZE, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment());
            int uboBlockSize = spriteUboSize * this.mipLevelCount;
            ByteBuffer spriteUboBuffer = MemoryUtil.memAlloc(animatedSpriteCount * uboBlockSize);
            int animationIndex = 0;

            for (TextureAtlasSprite sprite : this.sprites) {
                if (sprite.isAnimated()) {
                    sprite.uploadSpriteUbo(spriteUboBuffer, animationIndex * uboBlockSize, this.maxMipLevel, this.width, this.height, spriteUboSize);
                    animationIndex++;
                }
            }

            GpuBuffer spriteUbos = RenderSystem.getDevice().createBuffer(() -> this.location + " sprite UBOs", 128, spriteUboBuffer);
            animationIndex = 0;

            for (TextureAtlasSprite sprite : this.sprites) {
                if (sprite.isAnimated()) {
                    SpriteContents.AnimationState animationState = sprite.createAnimationState(
                        spriteUbos.slice(animationIndex * uboBlockSize, uboBlockSize), spriteUboSize
                    );
                    // MODIFIED for porting: was sodium-extra's animation MixinTextureAtlas#upload
                    // (@Redirect on TextureAtlasSprite#createAnimationState) - remember which sprite the state belongs to.
                    if (me.flashyreese.mods.sodiumextra.client.config.SodiumExtraFeatures.ANIMATION && animationState instanceof me.flashyreese.mods.sodiumextra.common.util.AnimationStateExtended extended) {
                        extended.sodium_extra$setSprite(sprite);
                    }

                    animationIndex++;
                    if (animationState != null) {
                        animationStates.add(animationState);
                    }
                }
            }

            this.spriteUbos = spriteUbos;
            this.animatedTexturesStates = animationStates.build();
            MemoryUtil.memFree(spriteUboBuffer);
        }

        this.uploadInitialContents();
        // MODIFIED for porting: was iris's texture pbr MixinTextureAtlas#iris$onUpload (@Inject RETURN)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.pbr.TextureTracker.INSTANCE.trackTexture(this.texture.iris$getGlId(), this);
        }
        if (SharedConstants.DEBUG_DUMP_TEXTURE_ATLAS) {
            Path dumpDir = TextureUtil.getDebugTexturePath();

            try {
                Files.createDirectories(dumpDir);
                this.dumpContents(this.location, dumpDir);
            } catch (Exception e) {
                LOGGER.warn("Failed to dump atlas contents to {}", dumpDir);
            }
        }

        // MODIFIED for porting: sodium core.render TextureAtlasMixin#sodium$deleteSpriteFinder (RETURN) - the sprite lookup
        // built from this atlas has to be discarded when the atlas is re-uploaded.
        if (this.location.equals(TextureAtlas.LOCATION_BLOCKS)) {
            net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache.resetSpriteFinder();
            this.sodium$isBlocks = true;
        } else if (this.location.equals(TextureAtlas.LOCATION_ITEMS)) {
            net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache.resetItemSpriteFinder();
            this.sodium$isBlocks = false;
        }
    }

    // MODIFIED for porting: sodium core.render TextureAtlasMixin @Unique field
    private boolean sodium$isBlocks = false;

    // MODIFIED for porting: was sodium's core.render TextureAtlasMixin#sodium$getSpriteFinder
    @Override
    public net.caffeinemc.mods.sodium.client.render.texture.SodiumSpriteFinder sodium$getSpriteFinder() {
        return new net.caffeinemc.mods.sodium.client.render.texture.SodiumSpriteFinderImpl(
            this.texturesByName,
            this.missingSprite,
            this.sodium$isBlocks
                ? net.caffeinemc.mods.sodium.client.render.model.SodiumQuadAtlas.BLOCK
                : net.caffeinemc.mods.sodium.client.render.model.SodiumQuadAtlas.ITEM
        );
    }

    private void uploadInitialContents() {
        GpuDevice device = RenderSystem.getDevice();
        int spriteUboSize = Mth.roundToward(SpriteContents.UBO_SIZE, device.getDeviceInfo().limits().minUniformOffsetAlignment());
        int uboBlockSize = spriteUboSize * this.mipLevelCount;
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
        List<TextureAtlasSprite> staticSprites = this.sprites.stream().filter(s -> !s.isAnimated()).toList();
        List<GpuTextureView[]> scratchTextures = new ArrayList<>();
        ByteBuffer buffer = MemoryUtil.memAlloc(staticSprites.size() * uboBlockSize);

        for (int i = 0; i < staticSprites.size(); i++) {
            TextureAtlasSprite sprite = staticSprites.get(i);
            sprite.uploadSpriteUbo(buffer, i * uboBlockSize, this.maxMipLevel, this.width, this.height, spriteUboSize);
            GpuTexture scratchTexture = device.createTexture(
                () -> sprite.contents().name().toString(),
                5,
                GpuFormat.RGBA8_UNORM,
                sprite.contents().width(),
                sprite.contents().height(),
                1,
                this.mipLevelCount
            );
            GpuTextureView[] views = new GpuTextureView[this.mipLevelCount];

            for (int level = 0; level <= this.maxMipLevel; level++) {
                sprite.uploadFirstFrame(scratchTexture, level);
                views[level] = device.createTextureView(scratchTexture);
            }

            scratchTextures.add(views);
        }

        try (GpuBuffer ubo = device.createBuffer(() -> "SpriteAnimationInfo", 128, buffer)) {
            for (int level = 0; level < this.mipLevelCount; level++) {
                try (RenderPass renderPass = RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(() -> "Animate " + this.location, this.mipViews[level], Optional.empty())) {
                    RenderSystem.bindDefaultUniforms(renderPass);
                    renderPass.setPipeline(RenderPipelines.ANIMATE_SPRITE_BLIT);

                    for (int i = 0; i < staticSprites.size(); i++) {
                        renderPass.bindTexture("Sprite", scratchTextures.get(i)[level], sampler);
                        renderPass.setUniform("SpriteAnimationInfo", ubo.slice(i * uboBlockSize + level * spriteUboSize, SpriteContents.UBO_SIZE));
                        renderPass.draw(6, 1, 0, 0);
                    }
                }
            }
        }

        for (GpuTextureView[] views : scratchTextures) {
            for (GpuTextureView view : views) {
                view.close();
                view.texture().close();
            }
        }

        MemoryUtil.memFree(buffer);
        this.uploadAnimationFrames();
    }

    @Override
    public void dumpContents(final Identifier selfId, final Path dir) throws IOException {
        String outputId = selfId.toDebugFileName();
        TextureUtil.writeAsPNG(dir, outputId, this.getTexture(), this.maxMipLevel, argb -> argb);
        dumpSpriteNames(dir, outputId, this.texturesByName);
    }

    private static void dumpSpriteNames(final Path dir, final String outputId, final Map<Identifier, TextureAtlasSprite> regions) {
        Path outputPath = dir.resolve(outputId + ".txt");

        try (Writer output = Files.newBufferedWriter(outputPath)) {
            for (Entry<Identifier, TextureAtlasSprite> e : regions.entrySet().stream().sorted(Entry.comparingByKey()).toList()) {
                TextureAtlasSprite value = e.getValue();
                output.write(
                    String.format(
                        Locale.ROOT,
                        "%s\tx=%d\ty=%d\tw=%d\th=%d%n",
                        e.getKey(),
                        value.getX(),
                        value.getY(),
                        value.contents().width(),
                        value.contents().height()
                    )
                );
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to write file {}", outputPath, e);
        }
    }

    public void cycleAnimationFrames() {
        if (this.texture != null) {
            for (SpriteContents.AnimationState animationState : this.animatedTexturesStates) {
                // MODIFIED for porting: was sodium-extra's animation MixinTextureAtlas#cycleAnimationFrames
                // (@Redirect on SpriteContents$AnimationState#tick)
                if (me.flashyreese.mods.sodiumextra.client.config.SodiumExtraFeatures.ANIMATION) {
                    if (animationState instanceof me.flashyreese.mods.sodiumextra.common.util.AnimationStateExtended extended
                        && me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod.options().animationSettings.animation
                        && me.flashyreese.mods.sodiumextra.common.util.AnimatedSpriteFilter.shouldAnimate(extended.sodium_extra$getSprite().contents().name())) {
                        animationState.tick();
                    }
                } else {
                    animationState.tick();
                }
            }

            this.uploadAnimationFrames();
        }

        // MODIFIED for porting: was iris's texture pbr MixinTextureAtlas#iris$onTailCycleAnimationFrames (@Inject TAIL)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && this.iris$pbrHolder != null) {
            this.iris$pbrHolder.cycleAnimationFrames();
        }
    }

    private void uploadAnimationFrames() {
        if (this.animatedTexturesStates.stream().anyMatch(SpriteContents.AnimationState::needsToDraw)) {
            for (int level = 0; level <= this.maxMipLevel; level++) {
                try (RenderPass renderPass = RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(() -> "Animate " + this.location, this.mipViews[level], Optional.empty())) {
                    RenderSystem.bindDefaultUniforms(renderPass);

                    for (SpriteContents.AnimationState animationState : this.animatedTexturesStates) {
                        if (animationState.needsToDraw()) {
                            animationState.drawToAtlas(renderPass, animationState.getDrawUbo(level));
                        }
                    }
                }
            }
        }
    }

    @Override
    public void tick() {
        this.cycleAnimationFrames();
    }

    public TextureAtlasSprite getSprite(final Identifier location) {
        TextureAtlasSprite result = this.texturesByName.getOrDefault(location, this.missingSprite);
        if (result == null) {
            throw new IllegalStateException("Tried to lookup sprite, but atlas is not initialized");
        } else {
            // MODIFIED for porting: sodium features.textures.animations.tracking TextureAtlasMixin#preReturnSprite (RETURN)
            net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(result);
            return result;
        }
    }

    public TextureAtlasSprite missingSprite() {
        return Objects.requireNonNull(this.missingSprite, "Atlas not initialized");
    }

    public void clearTextureData() {
        this.sprites.forEach(TextureAtlasSprite::close);
        this.sprites = List.of();
        this.animatedTexturesStates.forEach(SpriteContents.AnimationState::close);
        this.animatedTexturesStates = List.of();
        this.texturesByName = Map.of();
        this.missingSprite = null;
        if (this.spriteUbos != null) {
            this.spriteUbos.close();
            this.spriteUbos = null;
        }
    }

    @Override
    protected void releaseTextures() {
        super.releaseTextures();

        for (GpuTextureView view : this.mipViews) {
            view.close();
        }
    }

    @Override
    public void close() {
        this.clearTextureData();
        super.close();
    }

    public Identifier location() {
        return this.location;
    }

    public int maxSupportedTextureSize() {
        return this.maxSupportedTextureSize;
    }

    // MODIFIED for porting: was sodium's TextureAtlasAccessor @Invoker("getWidth")
    @Override
    public int sodium$getWidth() {
        return this.getWidth();
    }

    // MODIFIED for porting: was sodium's TextureAtlasAccessor @Invoker("getHeight")
    @Override
    public int sodium$getHeight() {
        return this.getHeight();
    }

    int getWidth() {
        return this.width;
    }

    int getHeight() {
        return this.height;
    }
}