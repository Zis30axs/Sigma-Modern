package net.irisshaders.iris.shadows.frustum;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;

public class CullEverythingFrustum extends Frustum implements ViewportProvider, net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum,
	com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum { // MODIFIED for porting: iris compat.dh MixinCullEverythingFrustum
    /*
      MODIFIED for porting: was iris's compat.dh MixinCullEverythingFrustum. Distant Horizons calls these to cull its LOD
      terrain against iris's shadow frustum. Upstream applies the DH mixins only when DH is installed
      (DHMixinConfigPlugin#shouldApplyMixin), which cannot happen in this project - there is no mod loader - so the methods are
      simply never called here. They are still implemented so nothing is silently dropped.
    */
	@Override
	public void update(final int worldMinBlockY, final int worldMaxBlockY, final com.seibel.distanthorizons.api.objects.math.DhApiMat4f worldViewProjection) {
	}

	@Override
	public boolean intersects(final int lodBlockPosMinX, final int lodBlockPosMinZ, final int lodBlockWidth, final int lodDetailLevel) {
		return false;
	}

	private final Vector3d position = new Vector3d();

	public CullEverythingFrustum() {
		super(new Matrix4f(), new Matrix4f());
	}

	// For Immersive Portals
	// We return false here since isVisible is going to return false anyways.
	public boolean canDetermineInvisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		return false;
	}

	public boolean isVisible(AABB box) {
		return false;
	}

	@Override
	public void prepare(double d, double e, double f) {
		this.position.set(d, e, f);
	}

	@Override
	public Viewport sodium$createViewport() {
		return new Viewport(this, position);
	}

	@Override
	public boolean testAab(float v, float v1, float v2, float v3, float v4, float v5) {
		return false;
	}

	@Override
	public int intersectAab(float v, float v1, float v2, float v3, float v4, float v5) {
		return FrustumIntersection.OUTSIDE;
	}

	@Override
	public boolean testSection(float v, float v1, float v2) {
		return false;
	}

	@Override
	public boolean testSectionExpanded(float v, float v1, float v2, float v3) {
		return false;
	}
}
