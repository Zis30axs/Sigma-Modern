package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
/**
 * MODIFIED for porting: sodium's core.render.world ChunkSectionsToRenderMixin adds five mutable fields to this type, which a
 * record cannot have. It was therefore rewritten as a plain final class with the same components and accessors. The generated
 * {@code equals}/{@code hashCode}/{@code toString} are not used anywhere (the type is only constructed by
 * {@link net.minecraft.client.renderer.LevelRenderer#prepareChunkRenders} and consumed by {@link #renderGroup}).
 */
public final class ChunkSectionsToRender implements net.caffeinemc.mods.sodium.client.util.SodiumChunkSection {
    private final GpuTextureView textureView;
    private final EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroupsPerLayer;
    private final int maxIndicesRequired;
    private final GpuBufferSlice[] chunkSectionInfos;
    // MODIFIED for porting: sodium core.render.world ChunkSectionsToRenderMixin @Unique fields
    private net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer sodium$renderer;
    private net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices sodium$matrices;
    private double sodium$x;
    private double sodium$y;
    private double sodium$z;

    public ChunkSectionsToRender(
        final GpuTextureView textureView,
        final EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroupsPerLayer,
        final int maxIndicesRequired,
        final GpuBufferSlice[] chunkSectionInfos
    ) {
        this.textureView = textureView;
        this.drawGroupsPerLayer = drawGroupsPerLayer;
        this.maxIndicesRequired = maxIndicesRequired;
        this.chunkSectionInfos = chunkSectionInfos;
    }

    public GpuTextureView textureView() {
        return this.textureView;
    }

    public EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroupsPerLayer() {
        return this.drawGroupsPerLayer;
    }

    public int maxIndicesRequired() {
        return this.maxIndicesRequired;
    }

    public GpuBufferSlice[] chunkSectionInfos() {
        return this.chunkSectionInfos;
    }

    // MODIFIED for porting: was sodium's core.render.world ChunkSectionsToRenderMixin#sodium$setRendering
    @Override
    public void sodium$setRendering(
        final net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer renderer,
        final net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices matrices,
        final double x,
        final double y,
        final double z
    ) {
        this.sodium$renderer = renderer;
        this.sodium$matrices = matrices;
        this.sodium$x = x;
        this.sodium$y = y;
        this.sodium$z = z;
    }

    public void renderGroup(final ChunkSectionLayerGroup group, final GpuSampler sampler) {
        // MODIFIED for porting: sodium core.render.world ChunkSectionsToRenderMixin#sodium$renderGroup (HEAD, cancellable) -
        // once sodium is driving the terrain rendering, its own chunk renderer draws the layer group.
        if (this.sodium$renderer != null) {
            this.sodium$renderer.drawChunkLayer(group, this.sodium$matrices, this.sodium$x, this.sodium$y, this.sodium$z, sampler);
            return;
        }

        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer defaultIndexBuffer = this.maxIndicesRequired == 0 ? null : autoIndices.getBuffer(this.maxIndicesRequired);
        IndexType defaultIndexType = this.maxIndicesRequired == 0 ? null : autoIndices.type();
        ChunkSectionLayer[] layers = group.layers();
        Minecraft minecraft = Minecraft.getInstance();
        boolean wireframe = SharedConstants.DEBUG_HOTKEYS && minecraft.wireframe;
        RenderTarget renderTarget = group.outputTarget();

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                    () -> "Section layers for " + group.label(),
                    renderTarget.getColorTextureView(),
                    Optional.empty(),
                    renderTarget.getDepthTextureView(),
                    OptionalDouble.empty()
                )) {
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture("Sampler0", this.textureView, sampler);
            renderPass.bindTexture("Sampler2", minecraft.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

            for (ChunkSectionLayer layer : layers) {
                renderPass.setPipeline(wireframe ? RenderPipelines.WIREFRAME : layer.pipeline());
                Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> drawGroup = this.drawGroupsPerLayer.get(layer);

                for (List<RenderPass.Draw<GpuBufferSlice[]>> draws : drawGroup.values()) {
                    if (!draws.isEmpty()) {
                        if (layer == ChunkSectionLayer.TRANSLUCENT) {
                            draws = draws.reversed();
                        }

                        renderPass.drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, List.of("ChunkSection"), this.chunkSectionInfos);
                    }
                }
            }
        }
    }
}