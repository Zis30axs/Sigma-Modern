package net.minecraft.client.renderer.rendertype;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
/**
 * MODIFIED for porting: iris's MixinPreparedRenderType adds a mutable {@code wrapper} field (a {@code @Unique} field plus its
 * WrappedPreparedRenderType setter), which a record cannot have. It was therefore rewritten as a plain final class with the
 * same components and accessors; {@code equals}, {@code hashCode} and {@code toString} are implemented exactly as the record's
 * generated ones (the wrapper is set after construction and is deliberately not part of them).
 */
public final class PreparedRenderType implements net.irisshaders.iris.layer.WrappedPreparedRenderType {
    private final RenderPipeline pipeline;
    private final OutputTarget outputTarget;
    private final GpuBufferSlice dynamicTransforms;
    private final ScissorState scissorState;
    private final List<PreparedRenderType.Texture> textures;

    public PreparedRenderType(
        final RenderPipeline pipeline,
        final OutputTarget outputTarget,
        final GpuBufferSlice dynamicTransforms,
        final ScissorState scissorState,
        final List<PreparedRenderType.Texture> textures
    ) {
        this.pipeline = pipeline;
        this.outputTarget = outputTarget;
        this.dynamicTransforms = dynamicTransforms;
        this.scissorState = scissorState;
        this.textures = textures;
    }

    public RenderPipeline pipeline() {
        return this.pipeline;
    }

    public OutputTarget outputTarget() {
        return this.outputTarget;
    }

    public GpuBufferSlice dynamicTransforms() {
        return this.dynamicTransforms;
    }

    public ScissorState scissorState() {
        return this.scissorState;
    }

    public List<PreparedRenderType.Texture> textures() {
        return this.textures;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PreparedRenderType other)) {
            return false;
        }

        return java.util.Objects.equals(this.pipeline, other.pipeline)
            && java.util.Objects.equals(this.outputTarget, other.outputTarget)
            && java.util.Objects.equals(this.dynamicTransforms, other.dynamicTransforms)
            && java.util.Objects.equals(this.scissorState, other.scissorState)
            && java.util.Objects.equals(this.textures, other.textures);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(this.pipeline, this.outputTarget, this.dynamicTransforms, this.scissorState, this.textures);
    }

    @Override
    public String toString() {
        return "PreparedRenderType[pipeline="
            + this.pipeline
            + ", outputTarget="
            + this.outputTarget
            + ", dynamicTransforms="
            + this.dynamicTransforms
            + ", scissorState="
            + this.scissorState
            + ", textures="
            + this.textures
            + "]";
    }

    public void drawFromBuffer(final StagedVertexBuffer.ExecuteInfo info) {
        this.drawFromBuffer(info.vertexBuffer(), info.indexBuffer(), info.indexType(), info.baseVertex(), info.firstIndex(), info.indexCount());
    }

    // MODIFIED for porting: iris MixinPreparedRenderType @Unique field (its WrappedPreparedRenderType implementation)
    private net.irisshaders.iris.layer.RenderingWrapper iris$wrapper;

    @Override
    public void setRenderWrapper(final net.irisshaders.iris.layer.RenderingWrapper wrapper) {
        this.iris$wrapper = wrapper;
    }

    /**
     * MODIFIED for porting: was iris's MixinPreparedRenderType#iris$wrapBuffer (@WrapMethod on the six-argument
     * drawFromBuffer) - the wrapper sets up the shader program iris picked for this render type around the draw.
     */
    public void drawFromBuffer(
        final GpuBuffer vertexBuffer, final GpuBuffer indexBuffer, final IndexType indexType, final int baseVertex, final int firstIndex, final int indexCount
    ) {
        if (this.iris$wrapper != null) {
            this.iris$wrapper.setup();
        }

        try {
            this.iris$drawFromBuffer(vertexBuffer, indexBuffer, indexType, baseVertex, firstIndex, indexCount);
        } finally {
            if (this.iris$wrapper != null) {
                this.iris$wrapper.clear();
            }
        }
    }

    // MODIFIED for porting: original vanilla body of drawFromBuffer, wrapped above
    private void iris$drawFromBuffer(
        final GpuBuffer vertexBuffer, final GpuBuffer indexBuffer, final IndexType indexType, final int baseVertex, final int firstIndex, final int indexCount
    ) {
        RenderTarget renderTarget = this.outputTarget.getRenderTarget();
        GpuTextureView colorTexture = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride
            : renderTarget.getColorTextureView();
        GpuTextureView depthTexture = renderTarget.useDepth
            ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : renderTarget.getDepthTextureView())
            : null;

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "Immediate draw with " + this.pipeline, colorTexture, Optional.empty(), depthTexture, OptionalDouble.empty())) {
            renderPass.setPipeline(this.pipeline);
            if (this.scissorState.enabled()) {
                renderPass.enableScissor(this.scissorState.x(), this.scissorState.y(), this.scissorState.width(), this.scissorState.height());
            }

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", this.dynamicTransforms);
            renderPass.setVertexBuffer(0, vertexBuffer.slice());

            for (PreparedRenderType.Texture texture : this.textures) {
                renderPass.bindTexture(texture.name, texture.textureView, texture.sampler);
            }

            renderPass.setIndexBuffer(indexBuffer, indexType);
            renderPass.drawIndexed(indexCount, 1, firstIndex, baseVertex, 0);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public record Texture(String name, GpuTextureView textureView, GpuSampler sampler) {
    }
}