package net.minecraft.client.renderer;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class RenderBuffers implements AutoCloseable {
    private final SectionBufferBuilderPack fixedBufferPack = new SectionBufferBuilderPack();
    private final SectionBufferBuilderPool sectionBufferPool;
    private final StagedVertexBuffer stagedVertexBuffer;

    public RenderBuffers(final int maxSectionBuilders) {
        // MODIFIED for porting: sodium core.render.world RenderBuffersMixin#sodium$doNotAllocateChunks (@Redirect) -
        // sodium never uses the vanilla section buffers, so nothing is allocated for them.
        this.sectionBufferPool = new net.caffeinemc.mods.sodium.client.render.chunk.NonStoringBuilderPool();
        this.stagedVertexBuffer = new StagedVertexBuffer(() -> "Shared Buffer", 4194304);
    }

    public SectionBufferBuilderPack fixedBufferPack() {
        return this.fixedBufferPack;
    }

    public SectionBufferBuilderPool sectionBufferPool() {
        return this.sectionBufferPool;
    }

    public StagedVertexBuffer stagedVertexBuffer() {
        return this.stagedVertexBuffer;
    }

    public void endFrame() {
        this.stagedVertexBuffer.endFrame();
    }

    @Override
    public void close() {
        this.sectionBufferPool.close();
        this.stagedVertexBuffer.close();
    }
}