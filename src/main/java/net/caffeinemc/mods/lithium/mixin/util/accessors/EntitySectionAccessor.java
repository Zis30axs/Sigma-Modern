package net.caffeinemc.mods.lithium.mixin.util.accessors;

import net.minecraft.util.ClassInstanceMultiMap;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface EntitySectionAccessor<T> {
    ClassInstanceMultiMap<T> getCollection();
}
