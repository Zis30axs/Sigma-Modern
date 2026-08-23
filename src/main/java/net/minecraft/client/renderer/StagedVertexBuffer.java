package net.minecraft.client.renderer;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class StagedVertexBuffer implements AutoCloseable {
    private final ByteBufferBuilder stagingBuffer;
    private final StagedVertexBuffer.GpuBufferPool stagingGpuBufferPool;
    private final List<StagedVertexBuffer.Draw> draws = new ArrayList<>();
    private StagedVertexBuffer.@Nullable Draw lastBuildingDraw;
    private @Nullable BufferBuilder lastVertexBuilder;
    private final StagedVertexBuffer.GpuBufferPool vertexBufferPool;
    private final StagedVertexBuffer.GpuBufferPool indexBufferPool;
    private @Nullable GpuBuffer currentVertexBuffer;
    private @Nullable GpuBuffer currentIndexBuffer;

    public StagedVertexBuffer(final Supplier<String> label, final int initialCapacity) {
        this.stagingBuffer = new ByteBufferBuilder(initialCapacity);
        this.stagingGpuBufferPool = new StagedVertexBuffer.GpuBufferPool(() -> label.get() + " - Staging", 22);
        this.vertexBufferPool = new StagedVertexBuffer.GpuBufferPool(() -> label.get() + " - Vertex", 40);
        this.indexBufferPool = new StagedVertexBuffer.GpuBufferPool(() -> label.get() + " - Index", 72);
    }

    public StagedVertexBuffer.Draw appendDraw(final VertexFormat format, final PrimitiveTopology primitiveTopology) {
        return this.appendDraw(format, primitiveTopology, null);
    }

    public StagedVertexBuffer.Draw appendDraw(final VertexFormat format, final PrimitiveTopology primitiveTopology, final @Nullable VertexSorting quadSorting) {
        if (this.currentVertexBuffer != null) {
            throw new IllegalStateException("Cannot append draw after upload");
        }

        if (quadSorting != null && primitiveTopology != PrimitiveTopology.QUADS) {
            throw new IllegalArgumentException("Cannot sort draw with " + primitiveTopology);
        }

        StagedVertexBuffer.Draw draw = new StagedVertexBuffer.Draw(format, primitiveTopology, quadSorting);
        this.draws.add(draw);
        return draw;
    }

    /**
     * MODIFIED for porting: was iris's vertices.immediate MixinBufferSource#iris$redirectBegin (@WrapMethod) plus its @Unique
     * {@code iris$notRenderingLevel}. Upstream's comment: a quick optimization to disable the extended vertex format outside of
     * level rendering when a BufferSource is used - a heuristic that should hold almost always, given how BufferSource is used.
     */
    public VertexConsumer getVertexBuilder(final StagedVertexBuffer.Draw draw) {
        if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            return this.iris$getVertexBuilder(draw);
        }

        net.irisshaders.iris.vertices.ImmediateState.skipExtension.set(!net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel);

        try {
            return this.iris$getVertexBuilder(draw);
        } finally {
            net.irisshaders.iris.vertices.ImmediateState.skipExtension.set(false);
        }
    }

    // MODIFIED for porting: original vanilla body of getVertexBuilder, wrapped above
    private VertexConsumer iris$getVertexBuilder(final StagedVertexBuffer.Draw draw) {
        if (this.currentVertexBuffer != null) {
            throw new IllegalStateException("Cannot append draw after upload");
        }

        if (this.lastBuildingDraw == draw) {
            return Objects.requireNonNull(this.lastVertexBuilder);
        }

        this.finishLastVertexBuilder();
        this.lastBuildingDraw = draw;
        this.lastVertexBuilder = new BufferBuilder(this.stagingBuffer, draw.primitiveTopology, draw.format);
        return this.lastVertexBuilder;
    }

    private void finishLastVertexBuilder() {
        if (this.lastVertexBuilder != null) {
            MeshData mesh = this.lastVertexBuilder.build();
            if (mesh != null) {
                Objects.requireNonNull(this.lastBuildingDraw).append(mesh);
            }

            this.lastVertexBuilder = null;
            this.lastBuildingDraw = null;
        }
    }

    /**
     * MODIFIED for porting: was iris's vertices.immediate MixinBufferSource#iris$beforeFlushBuffer (@Inject HEAD) and
     * #iris$afterFlushBuffer (@Inject RETURN).
     */
    public void upload() {
        boolean irisNotRenderingLevel = net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && !net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel;
        if (irisNotRenderingLevel) {
            net.irisshaders.iris.vertices.ImmediateState.renderWithExtendedVertexFormat = false;
        }

        try {
            this.iris$upload();
        } finally {
            if (irisNotRenderingLevel) {
                net.irisshaders.iris.vertices.ImmediateState.renderWithExtendedVertexFormat = true;
            }
        }
    }

    // MODIFIED for porting: original vanilla body of upload
    private void iris$upload() {
        if (this.currentVertexBuffer != null) {
            throw new IllegalStateException("Already uploaded");
        }

        if (!this.draws.isEmpty()) {
            this.finishLastVertexBuilder();
            int nextVertexOffset = 0;
            int nextIndexOffset = 0;

            for (StagedVertexBuffer.Draw draw : this.draws) {
                if (!draw.isEmpty()) {
                    draw.vertexOffset = Mth.roundToward(nextVertexOffset, draw.format.getVertexSize());
                    nextVertexOffset = draw.vertexOffset + draw.vertexBufferSize;
                    if (draw.quadSorting != null) {
                        IndexType indexType = draw.indexType();
                        draw.indexOffset = Mth.roundToward(nextIndexOffset, indexType.bytes);
                        nextIndexOffset = draw.indexOffset + draw.indexCount * indexType.bytes;
                    } else {
                        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(draw.primitiveTopology);
                        autoIndices.getBuffer(draw.indexCount);
                    }
                }
            }

            int vertexBufferSize = nextVertexOffset;
            int indexBufferSize = nextIndexOffset;
            if (vertexBufferSize != 0) {
                GpuDevice device = RenderSystem.getDevice();
                this.currentVertexBuffer = this.vertexBufferPool.acquire(device, vertexBufferSize);
                this.currentIndexBuffer = indexBufferSize > 0 ? this.indexBufferPool.acquire(device, indexBufferSize) : null;
                this.uploadDrawsToBuffers(device, this.draws, this.currentVertexBuffer, this.currentIndexBuffer, vertexBufferSize, indexBufferSize);
            }
        }
    }

    private void uploadDrawsToBuffers(
        final GpuDevice device,
        final List<StagedVertexBuffer.Draw> draws,
        final GpuBuffer vertexGpuBuffer,
        final @Nullable GpuBuffer indexGpuBuffer,
        final int vertexBufferSize,
        final int indexBufferSize
    ) {
        CommandEncoder commandEncoder = device.createCommandEncoder();
        int stagingBufferSize = vertexBufferSize + indexBufferSize;
        GpuBuffer stagingBuffer = this.stagingGpuBufferPool.acquire(device, stagingBufferSize);

        try (GpuBufferSlice.MappedView view = stagingBuffer.slice(0L, stagingBufferSize).map(false, true)) {
            ByteBuffer buffer = view.data();

            for (StagedVertexBuffer.Draw draw : draws) {
                if (!draw.isEmpty()) {
                    buffer.position(draw.vertexOffset);

                    for (ByteBufferBuilder.Result slice : draw.vertexBufferSlices) {
                        buffer.put(slice.byteBuffer());
                    }
                }
            }

            for (StagedVertexBuffer.Draw draw : draws) {
                if (!draw.isEmpty()) {
                    if (indexGpuBuffer != null && draw.quadSorting != null) {
                        MeshData.SortState sortState = new MeshData.SortState(decodeSortingPoints(draw), draw.indexType());
                        buffer.position(vertexBufferSize + draw.indexOffset);
                        sortState.writeSortedIndexBuffer(buffer, draw.quadSorting);
                    }

                    draw.freeVertexData();
                }
            }
        }

        commandEncoder.copyToBuffer(stagingBuffer.slice(0L, vertexBufferSize), vertexGpuBuffer.slice(0L, vertexBufferSize));
        if (indexGpuBuffer != null) {
            commandEncoder.copyToBuffer(stagingBuffer.slice(vertexBufferSize, indexBufferSize), indexGpuBuffer.slice(0L, indexBufferSize));
        }
    }

    private static CompactVectorArray decodeSortingPoints(final StagedVertexBuffer.Draw draw) {
        VertexFormat format = draw.format;
        CompactVectorArray points = new CompactVectorArray(draw.vertexCount / 4);
        int offset = 0;

        for (ByteBufferBuilder.Result vertexBuffer : draw.vertexBufferSlices) {
            int vertexCount = vertexBuffer.size() / format.getVertexSize();
            // MODIFIED for porting: sodium features.render.immediate.buffer_builder.sorting
            // StagedVertexBufferMixin#sodium$selectClosestSortingPoints (@WrapOperation around decodeQuadCentroids)
            sodium$decodeSortingPoints(vertexBuffer.byteBuffer(), vertexCount, format, points, offset, draw);
            offset += vertexCount / 4;
        }

        return points;
    }

    /**
     * MODIFIED for porting: was sodium's features.render.immediate.buffer_builder.sorting
     * StagedVertexBufferMixin#sodium$selectClosestSortingPoints. When the draw sorts by distance to the view-space origin,
     * the closest point on each (flat, rectangular) quad is a better sorting key than its centroid.
     */
    private static void sodium$decodeSortingPoints(
        final java.nio.ByteBuffer vertexBuffer,
        final int vertexCount,
        final VertexFormat format,
        final CompactVectorArray output,
        final int outputIndex,
        final StagedVertexBuffer.Draw draw
    ) {
        if (!net.caffeinemc.mods.sodium.client.SodiumClientMod.options().quality.useClosestPointEntitySort
            || draw.getQuadSorting() != VertexSorting.DISTANCE_TO_ORIGIN) {
            MeshData.decodeQuadCentroids(vertexBuffer, vertexCount, format, output, outputIndex);
            return;
        }

        com.mojang.blaze3d.vertex.VertexFormatElement positionElement = format.getElement("Position");
        if (positionElement == null) {
            throw new IllegalArgumentException("Cannot identify quad points with no position element");
        }

        int positionOffset = vertexBuffer.position() + positionElement.offset();
        int vertexStride = format.getVertexSize();
        int quadStride = vertexStride * 4;
        int quadCount = vertexCount / 4;
        org.joml.Vector3f scratch = new org.joml.Vector3f();

        for (int i = 0; i < quadCount; i++) {
            int offset0 = i * quadStride + positionOffset;
            int offset1 = offset0 + vertexStride;
            int offset2 = offset0 + vertexStride * 2;
            float v0x = vertexBuffer.getFloat(offset0 + 0);
            float v0y = vertexBuffer.getFloat(offset0 + 4);
            float v0z = vertexBuffer.getFloat(offset0 + 8);
            float v1x = vertexBuffer.getFloat(offset1 + 0);
            float v1y = vertexBuffer.getFloat(offset1 + 4);
            float v1z = vertexBuffer.getFloat(offset1 + 8);
            float v2x = vertexBuffer.getFloat(offset2 + 0);
            float v2y = vertexBuffer.getFloat(offset2 + 4);
            float v2z = vertexBuffer.getFloat(offset2 + 8);
            // Closest point on the quad (assuming it's flat and rectangular) to the camera at the view-space origin, which
            // may not be the centroid. The first point must be the corner shared by the other two edges of the rectangle.
            org.joml.Intersectionf.findClosestPointOnRectangle(v1x, v1y, v1z, v0x, v0y, v0z, v2x, v2y, v2z, 0.0F, 0.0F, 0.0F, scratch);
            output.set(outputIndex + i, scratch.x, scratch.y, scratch.z);
        }
    }

    public StagedVertexBuffer.@Nullable ExecuteInfo getExecuteInfo(final StagedVertexBuffer.Draw draw) {
        if (draw.isEmpty()) {
            return null;
        } else if (this.currentVertexBuffer == null) {
            throw new IllegalStateException("Cannot execute before upload");
        } else {
            int baseVertex = draw.vertexOffset / draw.format.getVertexSize();
            if (this.currentIndexBuffer != null && draw.quadSorting != null) {
                IndexType indexType = draw.indexType();
                int firstIndex = draw.indexOffset / indexType.bytes;
                return new StagedVertexBuffer.ExecuteInfo(this.currentVertexBuffer, this.currentIndexBuffer, indexType, baseVertex, firstIndex, draw.indexCount);
            } else {
                RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(draw.primitiveTopology);
                GpuBuffer indexBuffer = autoIndices.getBuffer(draw.indexCount);
                return new StagedVertexBuffer.ExecuteInfo(this.currentVertexBuffer, indexBuffer, autoIndices.type(), baseVertex, 0, draw.indexCount);
            }
        }
    }

    public void endDraw() {
        this.draws.clear();
        this.currentVertexBuffer = null;
        this.currentIndexBuffer = null;
    }

    public void endFrame() {
        this.endDraw();
        GpuDevice device = RenderSystem.getDevice();
        this.stagingGpuBufferPool.endFrame(device);
        this.vertexBufferPool.endFrame(device);
        this.indexBufferPool.endFrame(device);
    }

    @Override
    public void close() {
        this.stagingBuffer.close();
        this.stagingGpuBufferPool.close();
        this.vertexBufferPool.close();
        this.indexBufferPool.close();
    }

    @OnlyIn(Dist.CLIENT)
    // MODIFIED for porting: implements sodium's DrawAccessor
    // (features.render.immediate.buffer_builder.sorting DrawAccessor)
    public static class Draw implements net.caffeinemc.mods.sodium.mixin.features.render.immediate.buffer_builder.sorting.DrawAccessor {
        // MODIFIED for porting: was sodium's DrawAccessor @Accessor
        @Override
        public @Nullable VertexSorting getQuadSorting() {
            return this.quadSorting;
        }

        private final VertexFormat format;
        private final PrimitiveTopology primitiveTopology;
        private final @Nullable VertexSorting quadSorting;
        private final List<ByteBufferBuilder.Result> vertexBufferSlices = new ArrayList<>();
        private int vertexBufferSize;
        private int vertexCount;
        private int indexCount;
        private int vertexOffset;
        private int indexOffset;

        private Draw(final VertexFormat format, final PrimitiveTopology primitiveTopology, final @Nullable VertexSorting quadSorting) {
            this.format = format;
            this.primitiveTopology = primitiveTopology;
            this.quadSorting = quadSorting;
        }

        private void append(final MeshData mesh) {
            assert mesh.indexBuffer() == null;
            this.vertexBufferSlices.add(mesh.vertexBufferSlice());
            this.vertexBufferSize = this.vertexBufferSize + mesh.vertexBuffer().remaining();
            this.vertexCount = this.vertexCount + mesh.drawState().vertexCount();
            this.indexCount = this.indexCount + mesh.drawState().indexCount();
        }

        private IndexType indexType() {
            return IndexType.least(this.vertexCount);
        }

        private void freeVertexData() {
            this.vertexBufferSlices.forEach(ByteBufferBuilder.Result::close);
            this.vertexBufferSlices.clear();
        }

        public boolean isEmpty() {
            return this.vertexCount == 0;
        }
    }

    @OnlyIn(Dist.CLIENT)
    public record ExecuteInfo(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, IndexType indexType, int baseVertex, int firstIndex, int indexCount) {
    }

    @OnlyIn(Dist.CLIENT)
    private static class GpuBufferPool implements AutoCloseable {
        private static final int BUFFER_SIZE_INCREMENT = 262144;
        private static final int MAX_REUSE_SIZE_FACTOR = 4;
        private final Supplier<String> label;
        private final @GpuBuffer.Usage int usage;
        private final List<GpuBuffer> available = new ArrayList<>();
        private final List<GpuBuffer> usedThisFrame = new ArrayList<>();
        private final List<StagedVertexBuffer.GpuBufferPool.PendingRecycle> pendingRecycle = new ArrayList<>();

        private GpuBufferPool(final Supplier<String> label, final @GpuBuffer.Usage int usage) {
            this.label = label;
            this.usage = usage;
        }

        private void tryRecycleBuffers() {
            this.pendingRecycle.removeIf(buffer -> {
                List<GpuBuffer> recycled = buffer.tryRecycle();
                if (recycled != null) {
                    this.available.addAll(recycled);
                    return true;
                } else {
                    return false;
                }
            });
        }

        public GpuBuffer acquire(final GpuDevice device, final int minSize) {
            this.tryRecycleBuffers();
            int roundedMinSize = Mth.roundToward(minSize, 262144);
            GpuBuffer buffer = this.takeBestAvailable(roundedMinSize, roundedMinSize * 4);
            if (buffer == null) {
                buffer = device.createBuffer(this.label, this.usage, roundedMinSize);
            }

            this.usedThisFrame.add(buffer);
            return buffer;
        }

        private @Nullable GpuBuffer takeBestAvailable(final int minSize, final int maxSize) {
            int bestIndex = -1;
            long bestSize = maxSize + 1;

            for (int i = 0; i < this.available.size(); i++) {
                long size = this.available.get(i).size();
                if (size == minSize) {
                    return this.available.remove(i);
                }

                if (size > minSize && size < bestSize) {
                    bestIndex = i;
                    bestSize = size;
                }
            }

            return bestIndex == -1 ? null : this.available.remove(bestIndex);
        }

        public void endFrame(final GpuDevice device) {
            if (!this.usedThisFrame.isEmpty()) {
                GpuFence fence = device.createCommandEncoder().createFence();
                this.pendingRecycle.add(new StagedVertexBuffer.GpuBufferPool.PendingRecycle(List.copyOf(this.usedThisFrame), fence));
                this.usedThisFrame.clear();
            }

            if (!this.available.isEmpty()) {
                this.available.forEach(GpuBuffer::close);
                this.available.clear();
            }
        }

        @Override
        public void close() {
            this.available.forEach(GpuBuffer::close);
            this.usedThisFrame.forEach(GpuBuffer::close);
            this.pendingRecycle.forEach(StagedVertexBuffer.GpuBufferPool.PendingRecycle::close);
            this.available.clear();
            this.usedThisFrame.clear();
            this.pendingRecycle.clear();
        }

        @OnlyIn(Dist.CLIENT)
        private record PendingRecycle(List<GpuBuffer> buffers, GpuFence fence) implements AutoCloseable {
            public @Nullable List<GpuBuffer> tryRecycle() {
                if (this.fence.awaitCompletion(0L)) {
                    this.fence.close();
                    return this.buffers;
                } else {
                    return null;
                }
            }

            @Override
            public void close() {
                this.buffers.forEach(GpuBuffer::close);
                this.fence.close();
            }
        }
    }
}