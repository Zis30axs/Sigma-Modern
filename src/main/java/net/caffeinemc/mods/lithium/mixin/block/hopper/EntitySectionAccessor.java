package net.caffeinemc.mods.lithium.mixin.block.hopper;

import net.minecraft.util.ClassInstanceMultiMap;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly. Upstream declares
 * the same accessor once per mixin config option (see {@code mixin.util.accessors}); both declarations are kept.
 */
public interface EntitySectionAccessor<T> {
    ClassInstanceMultiMap<T> getCollection();
}
