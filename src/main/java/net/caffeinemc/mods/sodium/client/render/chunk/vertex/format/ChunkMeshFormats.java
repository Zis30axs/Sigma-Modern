package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;

public class ChunkMeshFormats {
    public static final ChunkVertexType COMPACT = new CompactChunkVertex();

    /**
     * MODIFIED for porting: was iris's compat.sodium MixinChunkMeshFormats#getCurrent (@Overwrite) - the terrain vertex format
     * is the one the loaded shader pack asks for.
     */
    public static ChunkVertexType getCurrent() {
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            return net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getVertexFormat();
        }

        return COMPACT;
    }
}
