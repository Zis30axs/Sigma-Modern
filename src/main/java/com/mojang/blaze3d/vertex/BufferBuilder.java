package com.mojang.blaze3d.vertex;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import java.nio.ByteOrder;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

@OnlyIn(Dist.CLIENT)
// MODIFIED for porting: implements sodium's VertexBufferWriter / BufferBuilderExtension
// (core.render.immediate.consumer BufferBuilderMixin), which lets sodium push whole batches of already-encoded vertices.
public class BufferBuilder implements VertexConsumer,
    net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter,
    net.caffeinemc.mods.sodium.client.render.vertex.buffer.BufferBuilderExtension,
    net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder { // MODIFIED for porting: iris vertices MixinBufferBuilder
    /**
     * MODIFIED for porting: sodium features.render.immediate.buffer_builder.intrinsics BufferBuilderMixin#putBakedQuad. For
     * the block vertex format sodium writes the whole quad in one go through its own encoder; other formats use the vanilla
     * path. Every {@code MemoryUtil.memPut*} call in this class was additionally redirected to
     * {@link net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics} by the same mixin (the four @Redirects on {@code *}).
     */
    @Override
    public void putBakedQuad(
        final PoseStack.Pose pose, final net.minecraft.client.resources.model.geometry.BakedQuad quad, final QuadInstance instance
    ) {
        if (!this.blockFormat) {
            // check for ENTITY
            VertexConsumer.super.putBakedQuad(pose, quad, instance);
            if (quad.materialInfo().sprite() != null) {
                net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(quad.materialInfo().sprite());
            }

            return;
        }

        net.caffeinemc.mods.sodium.client.render.immediate.model.BakedModelEncoder
            .writeQuadVertices(
                net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter.of(this),
                pose,
                (net.caffeinemc.mods.sodium.client.model.quad.BakedQuadView)(Object)quad,
                instance
            );
        if (quad.materialInfo().sprite() != null) {
            net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(quad.materialInfo().sprite());
        }
    }

    // MODIFIED for porting: everything in this block was sodium's core.render.immediate.consumer BufferBuilderMixin
    @Override
    public void sodium$duplicateVertex() {
        if (this.vertices == 0) {
            return;
        }

        long head = this.buffer.reserve(this.vertexSize);
        net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.copyMemory(head - this.vertexSize, head, this.vertexSize);
        this.vertices++;
    }

    @Override
    public void push(final org.lwjgl.system.MemoryStack stack, final long src, final int count, final VertexFormat format) {
        int length = count * this.vertexSize;
        // The buffer may change in the event of a resize, so we need to make sure that the pointer is retrieved *after* it
        long dst = this.buffer.reserve(length);
        if (format == this.format) {
            // The layout is the same, so we can just perform a memory copy.
            // The stride of a vertex format is always 4 bytes, so this aligned copy is always safe.
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.copyMemory(src, dst, length);
        } else {
            // The layout differs, so we need to perform a conversion on the vertex data
            this.sodium$copySlow(src, dst, count, format);
        }

        this.vertices += count;
        this.vertexPointer = dst + length - this.vertexSize;
        this.elementsToFill = 0;
        // MODIFIED for porting: was iris's vertices MixinBufferBuilder#iris$skipSodiumChange (@Inject TAIL of push, declared
        // @Dynamic("Used to skip endLastVertex if the last push was made by Sodium") with require = 0). This `push` is exactly
        // the sodium-added method it means, so the hook is unconditional here.
        this.iris$skipEndVertexOnce = true;
    }

    private void sodium$copySlow(final long src, final long dst, final int count, final VertexFormat format) {
        net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializerRegistry.instance()
            .get(format, this.format)
            .serialize(src, dst, count);
    }

    private static final int MAX_VERTEX_COUNT = 16777215;
    private static final long NOT_BUILDING = -1L;
    private static final long UNKNOWN_ELEMENT = -1L;
    private static final boolean IS_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    /**
     * MODIFIED for porting: iris vertices MixinBufferBuilder @Unique fields. Upstream's class comment: "Dynamically and
     * transparently extends the vanilla vertex formats with additional data".
     */
    private static final int IRIS$UNKNOWN_OFFSET = -1;

    private static final int IRIS$NORMAL_SEMANTIC_ID = 5;

    private static final int IRIS$NORMAL_MASK = 1 << IRIS$NORMAL_SEMANTIC_ID;

    private final net.irisshaders.iris.vertices.BufferBuilderPolygonView iris$polygon = new net.irisshaders.iris.vertices.BufferBuilderPolygonView();

    private final org.joml.Vector3f iris$normal = new org.joml.Vector3f();

    private final long[] iris$vertexOffsets = new long[4];

    private boolean iris$skipEndVertexOnce;

    private boolean iris$extending;

    private boolean iris$injectNormalAndUV1;

    private int iris$vertexCount;

    private int iris$currentBlock = -1;

    private byte iris$currentRenderType = -1;

    private byte iris$currentBlockEmission = -1;

    private int iris$currentLocalPosX;

    private int iris$currentLocalPosY;

    private int iris$currentLocalPosZ;

    private int iris$positionOffset = IRIS$UNKNOWN_OFFSET;

    private int iris$uvOffset = IRIS$UNKNOWN_OFFSET;

    private int iris$normalOffset = IRIS$UNKNOWN_OFFSET;

    private int iris$midTexOffset = IRIS$UNKNOWN_OFFSET;

    private int iris$tangentOffset = IRIS$UNKNOWN_OFFSET;

    private int iris$midBlockOffset = IRIS$UNKNOWN_OFFSET;

    private int iris$entityOffset = IRIS$UNKNOWN_OFFSET;

    private int iris$entityIdOffset = IRIS$UNKNOWN_OFFSET;

    // MODIFIED for porting: was iris's vertices MixinBufferBuilder#beginBlock / #endBlock
    @Override
    public void beginBlock(final int block, final byte renderType, final byte blockEmission, final int localPosX, final int localPosY, final int localPosZ) {
        this.iris$currentBlock = block;
        this.iris$currentRenderType = renderType;
        this.iris$currentBlockEmission = blockEmission;
        this.iris$currentLocalPosX = localPosX;
        this.iris$currentLocalPosY = localPosY;
        this.iris$currentLocalPosZ = localPosZ;
    }

    @Override
    public void endBlock() {
        this.iris$currentBlock = -1;
        this.iris$currentRenderType = -1;
        this.iris$currentBlockEmission = -1;
        this.iris$currentLocalPosX = 0;
        this.iris$currentLocalPosY = 0;
        this.iris$currentLocalPosZ = 0;
    }

    /*
      MODIFIED for porting: iris's vertices MixinBufferBuilder declares `implements BlockSensitiveBufferBuilder` but only
      defines beginBlock and endBlock; overrideBlock, restoreBlock and ignoreMidBlock are simply absent from the merged class,
      so calling one of them on a BufferBuilder throws AbstractMethodError upstream (only the chunk build path, which goes
      through ChunkModelBuilder, ever receives them). A concrete Java class must declare them, so they reproduce exactly that
      failure instead of inventing behaviour.
    */
    @Override
    public void overrideBlock(final int block) {
        throw new AbstractMethodError("BufferBuilder does not implement BlockSensitiveBufferBuilder#overrideBlock");
    }

    @Override
    public void restoreBlock() {
        throw new AbstractMethodError("BufferBuilder does not implement BlockSensitiveBufferBuilder#restoreBlock");
    }

    @Override
    public void ignoreMidBlock(final boolean b) {
        throw new AbstractMethodError("BufferBuilder does not implement BlockSensitiveBufferBuilder#ignoreMidBlock");
    }

    // MODIFIED for porting: was iris's vertices MixinBufferBuilder#iris$vertexAmountForExtendedData (@Unique)
    private int iris$vertexAmountForExtendedData() {
        if (this.primitiveTopology == PrimitiveTopology.QUADS) {
            return 4;
        } else if (this.primitiveTopology == PrimitiveTopology.TRIANGLES) {
            return 3;
        }

        return 0;
    }

    private final ByteBufferBuilder buffer;
    private long vertexPointer = -1L;
    private int vertices;
    private final VertexFormat format;
    private final PrimitiveTopology primitiveTopology;
    private final boolean blockFormat;
    private final boolean entityFormat;
    private final int vertexSize;
    private final int initialElementsToFill;
    private int elementsToFill;
    private boolean building = true;
    private static final int POSITION_SEMANTIC_ID = 0;
    private static final int COLOR_SEMANTIC_ID = 1;
    private static final int UV0_SEMANTIC_ID = 2;
    private static final int UV1_SEMANTIC_ID = 3;
    private static final int UV2_SEMANTIC_ID = 4;
    private static final int NORMAL_SEMANTIC_ID = 5;
    private static final int LINE_WIDTH_SEMANTIC_ID = 6;
    private static final String[] elementNames = new String[]{"Position", "Color", "UV0", "UV1", "UV2", "Normal", "LineWidth"};
    private final @Nullable VertexFormatElement[] elements = new VertexFormatElement[elementNames.length];

    public BufferBuilder(final ByteBufferBuilder buffer, final PrimitiveTopology primitiveTopology, VertexFormat format) {
        // MODIFIED for porting: was iris's vertices MixinBufferBuilder#iris$extendFormat (@ModifyVariable on <init>, argsOnly,
        // at the INVOKE of VertexFormat#contains) - the vanilla format is transparently swapped for iris's extended one.
        this.iris$injectNormalAndUV1 = false;
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()
            && !net.irisshaders.iris.vertices.ImmediateState.skipExtension.get()
            && net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel
            && net.irisshaders.iris.Iris.isPackInUseQuick()) {
            if (format.equals(DefaultVertexFormat.BLOCK) || format.equals(net.irisshaders.iris.vertices.IrisVertexFormats.TERRAIN)) {
                this.iris$extending = true;
                format = net.irisshaders.iris.vertices.IrisVertexFormats.TERRAIN;
            } else if (format.equals(DefaultVertexFormat.ENTITY) || format.equals(net.irisshaders.iris.vertices.IrisVertexFormats.ENTITY)) {
                this.iris$extending = true;
                format = net.irisshaders.iris.vertices.IrisVertexFormats.ENTITY;
            } else if (format.equals(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR) || format.equals(net.irisshaders.iris.vertices.IrisVertexFormats.GLYPH)) {
                this.iris$extending = true;
                this.iris$injectNormalAndUV1 = true;
                format = net.irisshaders.iris.vertices.IrisVertexFormats.GLYPH;
            }
        }

        if (!format.contains("Position")) {
            throw new IllegalArgumentException("Cannot build mesh with no position element");
        }

        this.buffer = buffer;
        this.primitiveTopology = primitiveTopology;
        this.format = format;
        this.vertexSize = format.getVertexSize();
        int elementsMask = 0;

        for (int i = 0; i < elementNames.length; i++) {
            String elementName = elementNames[i];
            VertexFormatElement element = format.getElement(elementName);
            if (element != null) {
                elementsMask |= 1 << i;
            }

            this.elements[i] = element;
        }

        this.initialElementsToFill = elementsMask & -2;
        this.blockFormat = format == DefaultVertexFormat.BLOCK;
        this.entityFormat = format == DefaultVertexFormat.ENTITY;
        // MODIFIED for porting: was iris's vertices MixinBufferBuilder#iris$cacheOffsets (@Inject into <init> at TAIL)
        if (this.iris$extending) {
            this.iris$positionOffset = net.irisshaders.iris.vertices.IrisVertexFormats.getOffset(this.format, DefaultVertexFormat.POSITION_SEMANTIC_NAME);
            this.iris$uvOffset = net.irisshaders.iris.vertices.IrisVertexFormats.getOffset(this.format, DefaultVertexFormat.UV0_SEMANTIC_NAME);
            this.iris$normalOffset = net.irisshaders.iris.vertices.IrisVertexFormats.getOffset(this.format, DefaultVertexFormat.NORMAL_SEMANTIC_NAME);
            this.iris$midTexOffset = net.irisshaders.iris.vertices.IrisVertexFormats.getOffset(this.format, net.irisshaders.iris.vertices.IrisVertexFormats.MID_TEXTURE_ATTRIBUTE);
            this.iris$tangentOffset = net.irisshaders.iris.vertices.IrisVertexFormats.getOffset(this.format, net.irisshaders.iris.vertices.IrisVertexFormats.TANGENT_ATTRIBUTE);
            this.iris$midBlockOffset = net.irisshaders.iris.vertices.IrisVertexFormats.getOffset(this.format, net.irisshaders.iris.vertices.IrisVertexFormats.MID_BLOCK_ATTRIBUTE);
            this.iris$entityOffset = net.irisshaders.iris.vertices.IrisVertexFormats.getOffset(this.format, net.irisshaders.iris.vertices.IrisVertexFormats.ENTITY_ATTRIBUTE);
            this.iris$entityIdOffset = net.irisshaders.iris.vertices.IrisVertexFormats.getOffset(this.format, net.irisshaders.iris.vertices.IrisVertexFormats.ENTITY_ID_ATTRIBUTE);
        }
    }

    public @Nullable MeshData build() {
        this.ensureBuilding();
        this.endLastVertex();
        MeshData mesh = this.storeMesh();
        this.building = false;
        this.vertexPointer = -1L;
        return mesh;
    }

    public MeshData buildOrThrow() {
        MeshData buffer = this.build();
        if (buffer == null) {
            throw new IllegalStateException("BufferBuilder was empty");
        } else {
            return buffer;
        }
    }

    private void ensureBuilding() {
        if (!this.building) {
            throw new IllegalStateException("Not building!");
        }
    }

    private @Nullable MeshData storeMesh() {
        if (this.vertices == 0) {
            return null;
        }

        ByteBufferBuilder.Result vertexBuffer = this.buffer.build();
        if (vertexBuffer == null) {
            return null;
        }

        int indices = this.primitiveTopology.indexCount(this.vertices);
        IndexType indexType = IndexType.least(this.vertices);
        return new MeshData(vertexBuffer, new MeshData.DrawState(this.format, this.vertices, indices, this.primitiveTopology, indexType));
    }

    private long beginVertex() {
        this.ensureBuilding();
        this.endLastVertex();
        if (this.vertices >= 16777215) {
            throw new IllegalStateException("Trying to write too many vertices (>16777215) into BufferBuilder");
        }

        this.vertices++;
        long pointer = this.buffer.reserve(this.vertexSize);
        this.vertexPointer = pointer;
        return pointer;
    }

    private long beginElement(final int semanticID) {
        int oldElements = this.elementsToFill;
        int newElements = oldElements & ~(1 << semanticID);
        VertexFormatElement element = this.elements[semanticID];
        if (newElements != oldElements && element != null) {
            this.elementsToFill = newElements;
            long vertexPointer = this.vertexPointer;
            if (vertexPointer == -1L) {
                throw new IllegalArgumentException("Not currently building vertex");
            } else {
                return vertexPointer + element.offset();
            }
        } else {
            return -1L;
        }
    }

    private void endLastVertex() {
        // MODIFIED for porting: was iris's vertices MixinBufferBuilder#iris$beforeNext (@Inject HEAD)
        this.iris$beforeNext();

        if (this.vertices != 0) {
            if (this.elementsToFill != 0) {
                String missingElements = IntStream.range(0, elementNames.length)
                    .filter(i -> (this.elementsToFill & i) != 0)
                    .mapToObj(i -> elementNames[i])
                    .collect(Collectors.joining(", "));
                throw new IllegalStateException("Missing elements in vertex: " + missingElements);
            }

            if (this.primitiveTopology == PrimitiveTopology.LINES) {
                long pointer = this.buffer.reserve(this.vertexSize);
                MemoryUtil.memCopy(pointer - this.vertexSize, pointer, this.vertexSize);
                this.vertices++;
            }
        }
    }

    // MODIFIED for porting: was the body of iris's vertices MixinBufferBuilder#iris$beforeNext
    private void iris$beforeNext() {
        if (this.vertices == 0 || !this.iris$extending) {
            return;
        }

        if (this.iris$injectNormalAndUV1 && (this.elementsToFill & IRIS$NORMAL_MASK) != 0) {
            this.setNormal(0.0F, 1.0F, 0.0F);
        }

        if (this.iris$skipEndVertexOnce) {
            this.iris$skipEndVertexOnce = false;
            return;
        }

        int vertexAmount = this.iris$vertexAmountForExtendedData();
        if (vertexAmount == 0) {
            return;
        }

        this.iris$vertexOffsets[this.iris$vertexCount] = this.vertexPointer - ((net.irisshaders.iris.vertices.MojangBufferAccessor)this.buffer).getPointer();
        this.iris$vertexCount++;

        if (this.iris$vertexCount == vertexAmount) {
            this.iris$fillExtendedData(vertexAmount);
        }
    }

    // MODIFIED for porting: was iris's vertices MixinBufferBuilder#fillExtendedData (@Unique)
    private void iris$fillExtendedData(final int vertexAmount) {
        this.iris$vertexCount = 0;

        if (this.iris$positionOffset == IRIS$UNKNOWN_OFFSET
            || this.iris$uvOffset == IRIS$UNKNOWN_OFFSET
            || this.iris$midTexOffset == IRIS$UNKNOWN_OFFSET
            || this.iris$normalOffset == IRIS$UNKNOWN_OFFSET
            || this.iris$tangentOffset == IRIS$UNKNOWN_OFFSET) {
            java.util.Arrays.fill(this.iris$vertexOffsets, 0);
            return;
        }

        long basePointer = ((net.irisshaders.iris.vertices.MojangBufferAccessor)this.buffer).getPointer();
        this.iris$polygon.setup(basePointer, this.iris$vertexOffsets, this.iris$positionOffset, this.iris$uvOffset);
        float midU = 0.0F;
        float midV = 0.0F;

        for (int vertex = 0; vertex < vertexAmount; vertex++) {
            midU += this.iris$polygon.u(vertex);
            midV += this.iris$polygon.v(vertex);
        }

        midU /= vertexAmount;
        midV /= vertexAmount;

        if (vertexAmount == 3) {
            // NormalHelper.computeFaceNormalTri(normal, polygon); // Removed to enable smooth shaded triangles. Mods rendering
            // triangles with bad normals need to recalculate their normals manually or otherwise shading might be inconsistent.
            for (int vertex = 0; vertex < vertexAmount; vertex++) {
                long newPointer = basePointer + this.iris$vertexOffsets[vertex];
                int vertexNormal = org.lwjgl.system.MemoryUtil.memGetInt(newPointer + this.iris$normalOffset); // retrieve per-vertex normal
                int tangent = net.irisshaders.iris.vertices.NormalHelper
                    .computeTangentSmooth(
                        net.irisshaders.iris.vertices.NormI8.unpackX(vertexNormal),
                        net.irisshaders.iris.vertices.NormI8.unpackY(vertexNormal),
                        net.irisshaders.iris.vertices.NormI8.unpackZ(vertexNormal),
                        this.iris$polygon
                    );
                org.lwjgl.system.MemoryUtil.memPutFloat(newPointer + this.iris$midTexOffset, midU);
                org.lwjgl.system.MemoryUtil.memPutFloat(newPointer + this.iris$midTexOffset + 4, midV);
                org.lwjgl.system.MemoryUtil.memPutInt(newPointer + this.iris$tangentOffset, tangent);
            }
        } else {
            // TODO: Temporary fix for EMI item batching
            boolean recalculateNormal = net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel;
            net.irisshaders.iris.vertices.NormalHelper.computeFaceNormal(this.iris$normal, this.iris$polygon);
            int packedNormal = 0;
            if (recalculateNormal) {
                packedNormal = net.irisshaders.iris.vertices.NormI8.pack(this.iris$normal.x, this.iris$normal.y, this.iris$normal.z, 0.0F);
            }

            int tangent = net.irisshaders.iris.vertices.NormalHelper
                .computeTangent(this.iris$normal.x, this.iris$normal.y, this.iris$normal.z, this.iris$polygon);

            for (int vertex = 0; vertex < vertexAmount; vertex++) {
                long newPointer = basePointer + this.iris$vertexOffsets[vertex];
                org.lwjgl.system.MemoryUtil.memPutFloat(newPointer + this.iris$midTexOffset, midU);
                org.lwjgl.system.MemoryUtil.memPutFloat(newPointer + this.iris$midTexOffset + 4, midV);
                if (recalculateNormal) {
                    org.lwjgl.system.MemoryUtil.memPutInt(newPointer + this.iris$normalOffset, packedNormal);
                }

                org.lwjgl.system.MemoryUtil.memPutInt(newPointer + this.iris$tangentOffset, tangent);
            }
        }

        java.util.Arrays.fill(this.iris$vertexOffsets, 0);
    }

    private static void putRgba(final long pointer, final int argb) {
        int abgr = ARGB.toABGR(argb);
        net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putInt(pointer, IS_LITTLE_ENDIAN ? abgr : Integer.reverseBytes(abgr));
    }

    private static void putPackedUv(final long pointer, final int packedUv) {
        if (IS_LITTLE_ENDIAN) {
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putInt(pointer, packedUv);
        } else {
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putShort(pointer, (short)(packedUv & 65535));
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putShort(pointer + 2L, (short)(packedUv >> 16 & 65535));
        }
    }

    @Override
    public VertexConsumer addVertex(final float x, final float y, final float z) {
        VertexFormatElement positionElement = this.elements[0];
        long pointer = this.beginVertex() + positionElement.offset();
        this.elementsToFill = this.initialElementsToFill;
        putVec3f(pointer, x, y, z);
        // MODIFIED for porting: was iris's vertices MixinBufferBuilder#iris$fillPerVertexData (@Inject RETURN) - writes the
        // extended per-vertex attributes (mid-block position, block/render-type id, entity ids).
        if (this.iris$extending && this.vertexPointer != -1L) {
            if (this.iris$midBlockOffset != IRIS$UNKNOWN_OFFSET) {
                long offset = this.vertexPointer + this.iris$midBlockOffset;
                org.lwjgl.system.MemoryUtil.memPutInt(
                    offset,
                    net.irisshaders.iris.vertices.ExtendedDataHelper
                        .computeMidBlock(x, y, z, this.iris$currentLocalPosX, this.iris$currentLocalPosY, this.iris$currentLocalPosZ)
                );
                org.lwjgl.system.MemoryUtil.memPutByte(offset + 3, this.iris$currentBlockEmission);
            }

            if (this.iris$entityOffset != IRIS$UNKNOWN_OFFSET) {
                long offset = this.vertexPointer + this.iris$entityOffset;
                org.lwjgl.system.MemoryUtil.memPutShort(offset, (short)this.iris$currentBlock);
                org.lwjgl.system.MemoryUtil.memPutShort(offset + 2, this.iris$currentRenderType);
            }

            if (this.iris$entityIdOffset != IRIS$UNKNOWN_OFFSET) {
                long offset = this.vertexPointer + this.iris$entityIdOffset;
                org.lwjgl.system.MemoryUtil.memPutShort(offset, (short)net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedEntity());
                org.lwjgl.system.MemoryUtil.memPutShort(offset + 2, (short)net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity());
                org.lwjgl.system.MemoryUtil.memPutShort(offset + 4, (short)net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedItem());
                org.lwjgl.system.MemoryUtil.memPutShort(offset + 6, (short)0);
            }
        }

        return this;
    }

    @Override
    public VertexConsumer setColor(final int r, final int g, final int b, final int a) {
        long pointer = this.beginElement(1);
        if (pointer != -1L) {
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putByte(pointer, (byte)r);
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putByte(pointer + 1L, (byte)g);
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putByte(pointer + 2L, (byte)b);
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putByte(pointer + 3L, (byte)a);
        }

        return this;
    }

    @Override
    public VertexConsumer setColor(final int color) {
        long pointer = this.beginElement(1);
        if (pointer != -1L) {
            putRgba(pointer, color);
        }

        return this;
    }

    @Override
    public VertexConsumer setUv(final float u, final float v) {
        long pointer = this.beginElement(2);
        if (pointer != -1L) {
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putFloat(pointer, u);
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putFloat(pointer + 4L, v);
        }

        return this;
    }

    @Override
    public VertexConsumer setUv1(final int u, final int v) {
        return this.uvShort((short)u, (short)v, 3);
    }

    @Override
    public VertexConsumer setOverlay(final int packedOverlayCoords) {
        long pointer = this.beginElement(3);
        if (pointer != -1L) {
            putPackedUv(pointer, packedOverlayCoords);
        }

        return this;
    }

    @Override
    public VertexConsumer setUv2(final int u, final int v) {
        return this.uvShort((short)u, (short)v, 4);
    }

    @Override
    public VertexConsumer setLight(final int packedLightCoords) {
        long pointer = this.beginElement(4);
        if (pointer != -1L) {
            putPackedUv(pointer, packedLightCoords);
        }

        return this;
    }

    private VertexConsumer uvShort(final short u, final short v, final int semanticID) {
        long pointer = this.beginElement(semanticID);
        if (pointer != -1L) {
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putShort(pointer, u);
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putShort(pointer + 2L, v);
        }

        return this;
    }

    @Override
    public VertexConsumer setNormal(final float x, final float y, final float z) {
        long pointer = this.beginElement(5);
        if (pointer != -1L) {
            putNormals(pointer, x, y, z);
        }

        return this;
    }

    @Override
    public VertexConsumer setLineWidth(final float width) {
        long pointer = this.beginElement(6);
        if (pointer != -1L) {
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putFloat(pointer, width);
        }

        return this;
    }

    private static byte normalIntValue(final float c) {
        return (byte)((int)(Mth.clamp(c, -1.0F, 1.0F) * 127.0F) & 0xFF);
    }

    private static void putVec3f(final long pointer, final float x, final float y, final float z) {
        net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putFloat(pointer, x);
        net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putFloat(pointer + 4L, y);
        net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putFloat(pointer + 8L, z);
    }

    private static void putNormals(final long pointer, final float nx, final float ny, final float nz) {
        net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putByte(pointer, normalIntValue(nx));
        net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putByte(pointer + 1L, normalIntValue(ny));
        net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putByte(pointer + 2L, normalIntValue(nz));
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
        if (this.blockFormat) {
            long pointer = this.beginVertex();
            putVec3f(pointer, x, y, z);
            putRgba(pointer + 12L, color);
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putFloat(pointer + 16L, u);
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putFloat(pointer + 20L, v);
            putPackedUv(pointer + 24L, lightCoords);
        } else if (this.entityFormat) {
            long pointer = this.beginVertex();
            putVec3f(pointer, x, y, z);
            putRgba(pointer + 12L, color);
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putFloat(pointer + 16L, u);
            net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics.putFloat(pointer + 20L, v);
            putPackedUv(pointer + 24L, overlayCoords);
            putPackedUv(pointer + 28L, lightCoords);
            putNormals(pointer + 32L, nx, ny, nz);
        } else {
            VertexConsumer.super.addVertex(x, y, z, color, u, v, overlayCoords, lightCoords, nx, ny, nz);
        }
    }
}