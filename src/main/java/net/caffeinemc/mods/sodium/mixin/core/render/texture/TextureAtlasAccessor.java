package net.caffeinemc.mods.sodium.mixin.core.render.texture;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface TextureAtlasAccessor {
    int sodium$getWidth();

    int sodium$getHeight();
}
