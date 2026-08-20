package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
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
import net.minecraft.client.renderer.shaderpack.ShaderPackRuntime;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record ChunkSectionsToRender(
    GpuTextureView textureView,
    EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroupsPerLayer,
    int maxIndicesRequired,
    GpuBufferSlice[] chunkSectionInfos
) {
    public void renderGroup(final ChunkSectionLayerGroup group, final GpuSampler sampler) {
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer defaultIndexBuffer = this.maxIndicesRequired == 0 ? null : autoIndices.getBuffer(this.maxIndicesRequired);
        IndexType defaultIndexType = this.maxIndicesRequired == 0 ? null : autoIndices.type();
        ChunkSectionLayer[] layers = group.layers();
        Minecraft minecraft = Minecraft.getInstance();
        boolean wireframe = SharedConstants.DEBUG_HOTKEYS && minecraft.wireframe;
        RenderTarget renderTarget = group.outputTarget();
        EnumMap<ChunkSectionLayer, RenderPipeline> pipelines = new EnumMap<>(ChunkSectionLayer.class);
        boolean requiresSeparatePasses = false;

        for (ChunkSectionLayer layer : layers) {
            RenderPipeline pipeline = wireframe ? RenderPipelines.WIREFRAME : ShaderPackRuntime.terrainPipeline(layer);
            pipelines.put(layer, pipeline);
            if (!wireframe && pipeline != layer.pipeline()) {
                requiresSeparatePasses = true;
            }
        }

        if (!requiresSeparatePasses) {
            this.renderCombinedGroup(
                group,
                layers,
                renderTarget,
                sampler,
                minecraft,
                pipelines,
                defaultIndexBuffer,
                defaultIndexType
            );
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        for (ChunkSectionLayer layer : layers) {
            Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> drawGroup = this.drawGroupsPerLayer.get(layer);
            if (drawGroup == null || drawGroup.isEmpty()) {
                continue;
            }

            try (RenderPass renderPass = encoder.createRenderPass(
                    ShaderPackRuntime.terrainPassDescriptor(encoder, layer, renderTarget, () -> "Section layer " + layer.label())
                )) {
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.bindTexture("Sampler0", this.textureView, sampler);
                renderPass.bindTexture("Sampler2", minecraft.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                renderPass.setPipeline(pipelines.get(layer));
                this.drawLayer(renderPass, layer, drawGroup, defaultIndexBuffer, defaultIndexType);
            }
        }
    }

    private void renderCombinedGroup(
        final ChunkSectionLayerGroup group,
        final ChunkSectionLayer[] layers,
        final RenderTarget renderTarget,
        final GpuSampler sampler,
        final Minecraft minecraft,
        final EnumMap<ChunkSectionLayer, RenderPipeline> pipelines,
        final GpuBuffer defaultIndexBuffer,
        final IndexType defaultIndexType
    ) {
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
                Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> drawGroup = this.drawGroupsPerLayer.get(layer);
                if (drawGroup == null || drawGroup.isEmpty()) {
                    continue;
                }
                renderPass.setPipeline(pipelines.get(layer));
                this.drawLayer(renderPass, layer, drawGroup, defaultIndexBuffer, defaultIndexType);
            }
        }
    }

    private void drawLayer(
        final RenderPass renderPass,
        final ChunkSectionLayer layer,
        final Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> drawGroup,
        final GpuBuffer defaultIndexBuffer,
        final IndexType defaultIndexType
    ) {
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
