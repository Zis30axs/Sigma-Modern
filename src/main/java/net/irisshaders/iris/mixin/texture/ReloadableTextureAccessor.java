package net.irisshaders.iris.mixin.texture;

import net.minecraft.resources.Identifier;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface ReloadableTextureAccessor {
	Identifier getLocation();
}
