package net.caffeinemc.mods.sodium.client.render.immediate.model;

import com.mojang.math.Quadrant;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.FaceBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import org.joml.Vector3f;

import static net.minecraft.client.resources.model.cuboid.ItemModelGenerator.MIN_Z;
import static net.minecraft.client.resources.model.cuboid.ItemModelGenerator.MAX_Z;
import static net.minecraft.client.resources.model.cuboid.ItemModelGenerator.UV_SHRINK;

/**
 * MODIFIED for porting: moved verbatim out of the loader-specific source set (upstream
 * {@code net.caffeinemc.mods.sodium.fabric.render.ImprovedItemModelBuilder}) into the common package next to its base
 * class. Its only dependencies were vanilla API and {@link ImprovedItemModelBuilderBase}; the NeoForge copy differs
 * only in the loader-specific quad builder it targets.
 */
public class ImprovedItemModelBuilder {

    public static void bakeSideQuads(
            QuadCollection.Builder builder,
            ModelBaker.Interner interner,
			BakedQuad.MaterialInfo materialInfo,
			ModelState modelState
	) {
		var sprite = materialInfo.sprite().contents();

		var xScale = 16.0F / sprite.width();
		var yScale = 16.0F / sprite.height();

		for (ImprovedItemModelBuilderBase.SideFace sideFace : ImprovedItemModelBuilderBase.buildSideFaces(sprite)) {
			var faceFacing = sideFace.facing();
			var faceAnchor = sideFace.anchor();
			var faceMin = sideFace.min();
			var faceMax = sideFace.max();

            // Calculate the start coordinate and length of the side quad using the side face properties, as described
            // in the diagram in FaceStorage.
			float minX = faceFacing.isHorizontal() ? faceMin : faceAnchor;
			float minY = faceFacing.isHorizontal() ? faceAnchor : faceMin;
			float length = faceMax - faceMin + 1.0F;

			var u0 = 0.0F;
			var v0 = 0.0F;

			var u1 = 0.0F;
			var v1 = 0.0F;

			if (faceFacing.isHorizontal()) {
				u0 = minX + UV_SHRINK;
				v0 = minY + UV_SHRINK;
				u1 = minX + length - UV_SHRINK;
				v1 = minY + 1.0F - UV_SHRINK;
			} else {
				u0 = minX + UV_SHRINK;
				v0 = minY + length - UV_SHRINK;
				u1 = minX + 1.0F - UV_SHRINK;
				v1 = minY + UV_SHRINK;
			}

			var fromX = minX;
			var fromY = minY;
			var toX = minX;
			var toY = minY;

			switch (faceFacing) {
				case UP -> {
                    toX = minX + length;
                }
				case LEFT -> {
                    toY = minY + length;
                }
				case DOWN -> {
					fromY = minY + 1.0F;
					toY = minY + 1.0F;
					toX = minX + length;
				}
				case RIGHT -> {
					fromX = minX + 1.0F;
					toX = minX + 1.0F;
					toY = minY + length;
				}
			}

			fromX *= xScale;
			fromY *= yScale;
			toX *= xScale;
			toY *= yScale;

			fromY = 16.0F - fromY;
			toY = 16.0F - toY;

			switch (faceFacing) {
				case RIGHT -> fromX = toX;
				case DOWN -> fromY = toY;
				case LEFT -> toX = fromX;
				case UP -> toY = fromY;
			}

			builder.addUnculledFace(
					FaceBakery.bakeQuad(
							interner,
							new Vector3f(fromX, fromY, MIN_Z),
							new Vector3f(toX, toY, MAX_Z),
							new CuboidFace.UVs(
									u0 * xScale,
									v0 * yScale,
									u1 * xScale,
									v1 * yScale
							),
							Quadrant.R0,
							materialInfo,
							faceFacing.getDirection(),
							modelState,
							null
					)
			);
		}
	}
}
