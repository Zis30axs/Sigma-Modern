package net.irisshaders.iris.mixin;

import net.minecraft.client.renderer.CloudRenderer;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface CloudRendererAccessor {
	CloudRenderer.TextureData getTexture();
}
