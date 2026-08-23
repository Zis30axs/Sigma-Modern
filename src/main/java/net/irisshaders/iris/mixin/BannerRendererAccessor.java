package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.world.item.DyeColor;

/**
 * MODIFIED for porting: was a Mixin accessor interface with static accessors; the members it reached are widened in the
 * vanilla class and the static methods below read them directly.
 */
public interface BannerRendererAccessor {
	// maDU59_ was here =D
	static <S> void iris$invokeSubmitPatternLayer(final SpriteGetter sprites,
	                                              final PoseStack poseStack,
	                                              final OrderedSubmitNodeCollector submitNodeCollector,
	                                              final int lightCoords,
	                                              final int overlayCoords,
	                                              final Model<S> model,
	                                              final S state,
	                                              final SpriteId sprite,
	                                              final DyeColor color,
	                                              final ModelFeatureRenderer.CrumblingOverlay breakProgress) {
		BannerRenderer.submitPatternLayer(
			sprites, poseStack, submitNodeCollector, lightCoords, overlayCoords, model, state, sprite, color, breakProgress
		);
	}
}
