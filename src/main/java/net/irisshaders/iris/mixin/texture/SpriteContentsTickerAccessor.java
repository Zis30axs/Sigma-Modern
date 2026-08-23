package net.irisshaders.iris.mixin.texture;

import net.minecraft.client.renderer.texture.SpriteContents.AnimatedTexture;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface SpriteContentsTickerAccessor {
	int getFrame();

	void setFrame(int frame);

	int getSubFrame();

	void setSubFrame(int subFrame);

	AnimatedTexture getAnimationInfo();
}
