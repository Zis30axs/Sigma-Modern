package net.caffeinemc.mods.sodium.mixin.features.textures;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface NativeImageAccessor {
    long sodium$getPixels();
}
