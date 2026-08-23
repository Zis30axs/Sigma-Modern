package net.irisshaders.iris.mixin;

import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface LevelRendererAccessor {
	EntityRenderDispatcher getEntityRenderDispatcher();

	RenderBuffers getRenderBuffers();

	void setRenderBuffers(RenderBuffers buffers);

	LevelRenderState getLevelRenderState();
}
