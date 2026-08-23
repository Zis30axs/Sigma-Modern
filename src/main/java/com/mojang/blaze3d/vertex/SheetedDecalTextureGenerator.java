package com.mojang.blaze3d.vertex;

import net.minecraft.core.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
// MODIFIED for porting: implements sodium's VertexBufferWriter (core.render.immediate.consumer
// SheetedDecalTextureGeneratorMixin), so batches of encoded vertices can get their overlay UVs generated in bulk.
public class SheetedDecalTextureGenerator implements VertexConsumer, net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter {
    private final VertexConsumer delegate;
    private final Matrix4f cameraInversePose;
    private final Matrix3f normalInversePose;
    private final float textureScale;
    private final Vector3f worldPos = new Vector3f();
    private final Vector3f normal = new Vector3f();
    private float x;
    private float y;
    private float z;

    public SheetedDecalTextureGenerator(final VertexConsumer delegate, final PoseStack.Pose cameraPose, final float textureScale) {
        this.delegate = delegate;
        this.cameraInversePose = new Matrix4f(cameraPose.pose()).invert();
        this.normalInversePose = new Matrix3f(cameraPose.normal()).invert();
        this.textureScale = textureScale;
        // MODIFIED for porting: sodium core.render.immediate.consumer SheetedDecalTextureGeneratorMixin#onInit (<init> RETURN)
        this.sodium$canUseIntrinsics = net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter.tryOf(this.delegate) != null;
    }

    // MODIFIED for porting: everything in this block was sodium's core.render.immediate.consumer
    // SheetedDecalTextureGeneratorMixin
    private boolean sodium$canUseIntrinsics;

    @Override
    public boolean canUseIntrinsics() {
        return this.sodium$canUseIntrinsics;
    }

    @Override
    public void push(final org.lwjgl.system.MemoryStack stack, final long ptr, final int count, final VertexFormat format) {
        sodium$transform(ptr, count, format, this.normalInversePose, this.cameraInversePose, this.textureScale);
        net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter.of(this.delegate).push(stack, ptr, count, format);
    }

    /**
     * Transforms the overlay UVs element of each vertex to create a perspective-mapped effect.
     *
     * @param ptr The buffer of vertices to transform
     * @param count The number of vertices to transform
     * @param format The format of the vertices
     * @param inverseNormalMatrix The inverted normal matrix
     * @param inverseTextureMatrix The inverted texture matrix
     * @param textureScale The amount which the overlay texture should be adjusted
     */
    private static void sodium$transform(
        long ptr,
        final int count,
        final VertexFormat format,
        final Matrix3f inverseNormalMatrix,
        final Matrix4f inverseTextureMatrix,
        final float textureScale
    ) {
        long stride = format.getVertexSize();
        int[] cache = net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatOffsetCache.getInstance().getCachedOffsets(format);
        int offsetPosition = cache[net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatOffsetCache.POSITION];
        int offsetColor = cache[net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatOffsetCache.COLOR];
        int offsetNormal = cache[net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatOffsetCache.NORMAL];
        int offsetTexture = cache[net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatOffsetCache.UV];
        int color = net.caffeinemc.mods.sodium.api.util.ColorABGR.pack(1.0F, 1.0F, 1.0F, 1.0F);
        Vector3f normal = new Vector3f(Float.NaN);
        org.joml.Vector4f position = new org.joml.Vector4f(Float.NaN);

        for (int vertexIndex = 0; vertexIndex < count; vertexIndex++) {
            position.x = net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.getFloat(ptr + offsetPosition + 0);
            position.y = net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.getFloat(ptr + offsetPosition + 4);
            position.z = net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.getFloat(ptr + offsetPosition + 8);
            position.w = 1.0F;
            int packedNormal = net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.getInt(ptr + offsetNormal);
            normal.x = net.caffeinemc.mods.sodium.api.util.NormI8.unpackX(packedNormal);
            normal.y = net.caffeinemc.mods.sodium.api.util.NormI8.unpackY(packedNormal);
            normal.z = net.caffeinemc.mods.sodium.api.util.NormI8.unpackZ(packedNormal);
            Vector3f transformedNormal = inverseNormalMatrix.transform(normal);
            Direction direction = Direction.getApproximateNearest(transformedNormal.x(), transformedNormal.y(), transformedNormal.z());
            org.joml.Vector4f transformedTexture = inverseTextureMatrix.transform(position);
            transformedTexture.rotateY(3.1415927F);
            transformedTexture.rotateX(-1.5707964F);
            transformedTexture.rotate(direction.getRotation());
            float textureU = -transformedTexture.x() * textureScale;
            float textureV = -transformedTexture.y() * textureScale;
            net.caffeinemc.mods.sodium.api.vertex.attributes.common.ColorAttribute.set(ptr + offsetColor, color);
            net.caffeinemc.mods.sodium.api.vertex.attributes.common.TextureAttribute.put(ptr + offsetTexture, textureU, textureV);
            ptr += stride;
        }
    }

    @Override
    public VertexConsumer addVertex(final float x, final float y, final float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(final int r, final int g, final int b, final int a) {
        this.delegate.setColor(-1);
        return this;
    }

    @Override
    public VertexConsumer setColor(final int color) {
        this.delegate.setColor(-1);
        return this;
    }

    @Override
    public VertexConsumer setUv(final float u, final float v) {
        return this;
    }

    @Override
    public VertexConsumer setUv1(final int u, final int v) {
        this.delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(final int u, final int v) {
        this.delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(final float x, final float y, final float z) {
        this.delegate.setNormal(x, y, z);
        Vector3f normal = this.normalInversePose.transform(x, y, z, this.normal);
        Direction direction = Direction.getApproximateNearest(normal.x(), normal.y(), normal.z());
        Vector3f worldPos = this.cameraInversePose.transformPosition(this.x, this.y, this.z, this.worldPos);
        worldPos.rotateY((float) Math.PI);
        worldPos.rotateX((float) (-Math.PI / 2));
        worldPos.rotate(direction.getRotation());
        this.delegate.setUv(-worldPos.x() * this.textureScale, -worldPos.y() * this.textureScale);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(final float width) {
        this.delegate.setLineWidth(width);
        return this;
    }
}