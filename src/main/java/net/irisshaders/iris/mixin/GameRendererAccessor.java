package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface GameRendererAccessor {
	boolean shouldRenderBlockOutlineA();

	CrossFrameResourcePool getResourcePool();

	void invokeBobView(CameraRenderState state, PoseStack target);

	void invokeBobHurt(CameraRenderState state, PoseStack target);
}
