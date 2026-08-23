package net.caffeinemc.mods.sodium.mixin.core.render.frustum;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface GameRendererAccessor {
    float getSpinningEffectTime();

    float getSpinningEffectSpeed();
}
