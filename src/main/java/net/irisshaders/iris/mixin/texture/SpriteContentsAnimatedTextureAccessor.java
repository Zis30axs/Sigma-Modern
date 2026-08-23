package net.irisshaders.iris.mixin.texture;

import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface SpriteContentsAnimatedTextureAccessor {
	List<SpriteContents.FrameInfo> getFrames();
}
