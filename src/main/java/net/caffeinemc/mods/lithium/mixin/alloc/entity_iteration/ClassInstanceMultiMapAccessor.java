package net.caffeinemc.mods.lithium.mixin.alloc.entity_iteration;

import java.util.List;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface ClassInstanceMultiMapAccessor<T> {

    List<T> getAllInstances();
}
