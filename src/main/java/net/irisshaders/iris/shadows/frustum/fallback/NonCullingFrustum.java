package net.irisshaders.iris.shadows.frustum.fallback;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.Vector3d;

public class NonCullingFrustum extends Frustum implements ViewportProvider, net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum,
	com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum { // MODIFIED for porting: iris compat.dh MixinNonCullingFrustum
    /*
      MODIFIED for porting: was iris's compat.dh MixinNonCullingFrustum. Distant Horizons calls these to cull its LOD
      terrain against iris's shadow frustum. Upstream applies the DH mixins only when DH is installed
      (DHMixinConfigPlugin#shouldApplyMixin), which cannot happen in this project - there is no mod loader - so the methods are
      simply never called here. They are still implemented so nothing is silently dropped.
    */
	@Override
	public void update(final int worldMinBlockY, final int worldMaxBlockY, final com.seibel.distanthorizons.api.objects.math.DhApiMat4f worldViewProjection) {
	}

	@Override
	public boolean intersects(final int lodBlockPosMinX, final int lodBlockPosMinZ, final int lodBlockWidth, final int lodDetailLevel) {
		return true;
	}

	private final Vector3d position = new Vector3d();

	public NonCullingFrustum() {
		super(new Matrix4f(), new Matrix4f());
	}

	public NonCullingFrustum(final Matrix4fc modelViewMatrix, final Matrix4f projectionMatrixForCulling) {
		super(modelViewMatrix, projectionMatrixForCulling);
	}

	// For Immersive Portals
	// NB: The shadow culling in Immersive Portals must be disabled, because when Advanced Shadow Frustum Culling
	//     is not active, we are at a point where we can make no assumptions how the shader pack uses the shadow
	//     pass beyond what it already tells us. So we cannot use any extra fancy culling methods.
	public boolean canDetermineInvisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		return false;
	}

	@Override
	public boolean isVisible(AABB box) {
		return true;
	}

	@Override
	public int cubeInFrustum(BoundingBox boundingBox) {
		return FrustumIntersection.INSIDE;
	}

	@Override
	public boolean pointInFrustum(double x, double y, double z) {
      return true;
   }

	@Override
	public double getCamX() {
		return this.position.x;
	}

	@Override
	public double getCamY() {
		return this.position.y;
	}

	@Override
	public double getCamZ() {
		return this.position.z;
	}

	@Override
	public void prepare(double d, double e, double f) {
		super.prepare(d, e, f);
		this.position.set(d, e, f);
	}

	@Override
	public Viewport sodium$createViewport() {
		return new Viewport(this, position);
	}

	@Override
	public boolean testAab(float v, float v1, float v2, float v3, float v4, float v5) {
		return true;
	}

	@Override
	public int intersectAab(float v, float v1, float v2, float v3, float v4, float v5) {
		return FrustumIntersection.INSIDE;
	}

	@Override
	public boolean testSection(float v, float v1, float v2) {
		return true;
	}

	@Override
	public boolean testSectionExpanded(float v, float v1, float v2, float v3) {
		return true;
	}
}
