package net.caffeinemc.mods.lithium.mixin.minimal_nonvanilla.spawning;

import net.minecraft.util.ClassInstanceMultiMap;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class implements it directly. Upstream declares the
 * same accessor once per mixin config option so the options can be toggled independently; all declarations are kept.
 */
public interface EntitySectionAccessor<T> {
    ClassInstanceMultiMap<T> getCollection();
}
