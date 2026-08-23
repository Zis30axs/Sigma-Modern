package net.caffeinemc.mods.sodium.mixin.core.render.world;

import org.joml.Matrix4f;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface FrustumAccessor {
    Matrix4f sodium$getMatrix();
}
