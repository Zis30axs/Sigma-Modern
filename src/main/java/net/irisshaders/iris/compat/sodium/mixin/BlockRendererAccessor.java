package net.irisshaders.iris.compat.sodium.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface BlockRendererAccessor {
	ChunkBuildBuffers getBuffers();
}
