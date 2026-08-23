package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.opengl.GlStateManager;

/**
 * MODIFIED for porting: was a Mixin accessor interface with static accessors; the members it reached are widened in the
 * vanilla class and the static methods below read them directly.
 */
public interface GlStateManagerAccessor {
	static GlStateManager.BlendState[] getBLEND() {
		return GlStateManager.BLEND;
	}

	static int[] getCOLOR_MASK() {
		return GlStateManager.COLOR_MASK;
	}

	static GlStateManager.DepthState getDEPTH() {
		return GlStateManager.DEPTH;
	}

	static int getActiveTexture() {
		return GlStateManager.activeTexture;
	}

	static GlStateManager.TextureState[] getTEXTURES() {
		return GlStateManager.TEXTURES;
	}
}
