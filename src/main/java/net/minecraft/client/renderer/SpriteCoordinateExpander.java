package net.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
// MODIFIED for porting: implements sodium's VertexBufferWriter (core.render.immediate.consumer
// SpriteCoordinateExpanderMixin), so batches of encoded vertices can be re-mapped into the sprite area in bulk.
public class SpriteCoordinateExpander implements VertexConsumer, net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter {
    private final VertexConsumer delegate;
    private final TextureAtlasSprite sprite;
    // MODIFIED for porting: sodium core.render.immediate.consumer SpriteCoordinateExpanderMixin @Unique fields
    private final boolean sodium$canUseIntrinsics;
    private final float sodium$minU;
    private final float sodium$minV;
    private final float sodium$maxU;
    private final float sodium$maxV;

    public SpriteCoordinateExpander(final VertexConsumer delegate, final TextureAtlasSprite sprite) {
        this.delegate = delegate;
        this.sprite = sprite;
        // MODIFIED for porting: sodium core.render.immediate.consumer SpriteCoordinateExpanderMixin#onInit (<init> RETURN)
        this.sodium$minU = sprite.getU0();
        this.sodium$minV = sprite.getV0();
        this.sodium$maxU = sprite.getU1();
        this.sodium$maxV = sprite.getV1();
        this.sodium$canUseIntrinsics = net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter.tryOf(this.delegate) != null;
    }

    // MODIFIED for porting: everything in this block was sodium's core.render.immediate.consumer SpriteCoordinateExpanderMixin
    @Override
    public boolean canUseIntrinsics() {
        return this.sodium$canUseIntrinsics;
    }

    @Override
    public void push(
        final org.lwjgl.system.MemoryStack stack, final long ptr, final int count, final com.mojang.blaze3d.vertex.VertexFormat format
    ) {
        sodium$transform(ptr, count, format, this.sodium$minU, this.sodium$minV, this.sodium$maxU, this.sodium$maxV);
        net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter.of(this.delegate).push(stack, ptr, count, format);
    }

    /**
     * Transforms the texture UVs for each vertex from their absolute coordinates into the sprite area specified by the
     * parameters.
     *
     * @param ptr The buffer of vertices to transform
     * @param count The number of vertices to transform
     * @param format The format of the vertices
     * @param minU The minimum X-coordinate of the sprite bounds
     * @param minV The minimum Y-coordinate of the sprite bounds
     * @param maxU The maximum X-coordinate of the sprite bounds
     * @param maxV The maximum Y-coordinate of the sprite bounds
     */
    private static void sodium$transform(
        long ptr,
        final int count,
        final com.mojang.blaze3d.vertex.VertexFormat format,
        final float minU,
        final float minV,
        final float maxU,
        final float maxV
    ) {
        long stride = format.getVertexSize();
        int[] cache = net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatOffsetCache.getInstance().getCachedOffsets(format);
        int offsetUV = cache[net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatOffsetCache.UV];
        // The width/height of the sprite
        float w = maxU - minU;
        float h = maxV - minV;

        for (int vertexIndex = 0; vertexIndex < count; vertexIndex++) {
            // The texture coordinates relative to the sprite bounds
            float u = net.caffeinemc.mods.sodium.api.vertex.attributes.common.TextureAttribute.getU(ptr + offsetUV);
            float v = net.caffeinemc.mods.sodium.api.vertex.attributes.common.TextureAttribute.getV(ptr + offsetUV);
            // The texture coordinates in absolute space on the sprite sheet
            net.caffeinemc.mods.sodium.api.vertex.attributes.common.TextureAttribute.put(ptr + offsetUV, minU + w * u, minV + h * v);
            ptr += stride;
        }
    }

    @Override
    public VertexConsumer addVertex(final float x, final float y, final float z) {
        return this.delegate.addVertex(x, y, z);
    }

    @Override
    public VertexConsumer setColor(final int r, final int g, final int b, final int a) {
        return this.delegate.setColor(r, g, b, a);
    }

    @Override
    public VertexConsumer setColor(final int color) {
        return this.delegate.setColor(color);
    }

    @Override
    public VertexConsumer setUv(final float u, final float v) {
        return this.delegate.setUv(this.sprite.getU(u), this.sprite.getV(v));
    }

    @Override
    public VertexConsumer setUv1(final int u, final int v) {
        return this.delegate.setUv1(u, v);
    }

    @Override
    public VertexConsumer setUv2(final int u, final int v) {
        return this.delegate.setUv2(u, v);
    }

    @Override
    public VertexConsumer setNormal(final float x, final float y, final float z) {
        return this.delegate.setNormal(x, y, z);
    }

    @Override
    public VertexConsumer setLineWidth(final float width) {
        this.delegate.setLineWidth(width);
        return this;
    }

    @Override
    public void addVertex(
        final float x,
        final float y,
        final float z,
        final int color,
        final float u,
        final float v,
        final int overlayCoords,
        final int lightCoords,
        final float nx,
        final float ny,
        final float nz
    ) {
        this.delegate.addVertex(x, y, z, color, this.sprite.getU(u), this.sprite.getV(v), overlayCoords, lightCoords, nx, ny, nz);
    }
}