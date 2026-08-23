package net.minecraft.client.renderer.texture;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.Transparency;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.resources.Identifier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

@OnlyIn(Dist.CLIENT)
// MODIFIED for porting: implements sodium's TextureAtlasSpriteExtension (features.textures.scan TextureAtlasSpriteMixin)
public class TextureAtlasSprite implements AutoCloseable,
    net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.TextureAtlasSpriteExtension,
    net.irisshaders.iris.mixin.texture.TextureAtlasSpriteAccessor { // MODIFIED for porting: iris texture TextureAtlasSpriteAccessor
    private final Identifier atlasLocation;
    private final SpriteContents contents;
    private final int x;
    private final int y;
    private final float u0;
    private final float u1;
    private final float v0;
    private final float v1;
    private final int padding;

    // MODIFIED for porting: was iris's texture TextureAtlasSpriteAccessor @Accessor("padding")
    @Override
    public int getPadding() {
        return this.padding;
    }

    protected TextureAtlasSprite(
        final Identifier atlasLocation, final SpriteContents contents, final int atlasWidth, final int atlasHeight, final int x, final int y, final int padding
    ) {
        this.atlasLocation = atlasLocation;
        this.contents = contents;
        this.padding = padding;
        this.x = x;
        this.y = y;
        this.u0 = (float)(x + padding) / atlasWidth;
        this.u1 = (float)(x + padding + contents.width()) / atlasWidth;
        this.v0 = (float)(y + padding) / atlasHeight;
        this.v1 = (float)(y + padding + contents.height()) / atlasHeight;
    }

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    public float getU0() {
        return this.u0;
    }

    public float getU1() {
        return this.u1;
    }

    public SpriteContents contents() {
        return this.contents;
    }

    public SpriteContents.@Nullable AnimationState createAnimationState(final GpuBufferSlice uboSlice, final int spriteUboSize) {
        // MODIFIED for porting: sodium features.textures.scan TextureAtlasSpriteMixin#hookTickerInstantiation
        // (@WrapOperation) - a non-vanilla animation state means the sprite's image contents are produced by something
        // sodium cannot predict, so its animation must always be uploaded.
        SpriteContents.AnimationState animationState = this.contents.createAnimationState(uboSlice, spriteUboSize);
        if (animationState != null && !SpriteContents.AnimationState.class.equals(animationState.getClass())) {
            this.sodium$hasUnknownImageContents = true;
        }

        return animationState;
    }

    // MODIFIED for porting: sodium features.textures.scan TextureAtlasSpriteMixin @Unique field
    private boolean sodium$hasUnknownImageContents;

    @Override
    public boolean sodium$hasUnknownImageContents() {
        return this.sodium$hasUnknownImageContents;
    }

    public Transparency transparency() {
        return this.contents.transparency();
    }

    public float getU(final float offset) {
        float diff = this.u1 - this.u0;
        return this.u0 + diff * offset;
    }

    public float getV0() {
        return this.v0;
    }

    public float getV1() {
        return this.v1;
    }

    public float getV(final float offset) {
        float diff = this.v1 - this.v0;
        return this.v0 + diff * offset;
    }

    public Identifier atlasLocation() {
        return this.atlasLocation;
    }

    @Override
    public String toString() {
        return "TextureAtlasSprite{contents='" + this.contents + "', u0=" + this.u0 + ", u1=" + this.u1 + ", v0=" + this.v0 + ", v1=" + this.v1 + "}";
    }

    public void uploadFirstFrame(final GpuTexture destination, final int level) {
        this.contents.uploadFirstFrame(destination, level);
    }

    public VertexConsumer wrap(final VertexConsumer buffer) {
        // MODIFIED for porting: sodium features.textures.animations.tracking TextureAtlasSpriteMixin#markSpriteAsActive
        // (HEAD)
        net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(this);
        return new SpriteCoordinateExpander(buffer, this);
    }

    public boolean isAnimated() {
        return this.contents.isAnimated();
    }

    public void uploadSpriteUbo(
        final ByteBuffer uboBuffer, final int startOffset, final int maxMipLevel, final int atlasWidth, final int atlasHeight, final int spriteUboSize
    ) {
        for (int level = 0; level <= maxMipLevel; level++) {
            Std140Builder.intoBuffer(MemoryUtil.memSlice(uboBuffer, startOffset + level * spriteUboSize, spriteUboSize))
                .putMat4f(new Matrix4f().ortho2D(0.0F, atlasWidth >> level, 0.0F, atlasHeight >> level))
                .putMat4f(
                    new Matrix4f()
                        .translate(this.x >> level, this.y >> level, 0.0F)
                        .scale(this.contents.width() + this.padding * 2 >> level, this.contents.height() + this.padding * 2 >> level, 1.0F)
                )
                .putFloat((float)this.padding / this.contents.width())
                .putFloat((float)this.padding / this.contents.height())
                .putInt(level);
        }
    }

    @Override
    public void close() {
        this.contents.close();
    }
}