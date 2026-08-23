package net.irisshaders.iris.mixin.texture;

import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface TextureAtlasAccessor {
	Map<Identifier, TextureAtlasSprite> getTexturesByName();

	int getMaxLevel();

	int callGetWidth();

	int callGetHeight();
}
