package net.caffeinemc.mods.lithium.mixin.block.hopper;

import java.util.List;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface NonNullListAccessor<T> {
    List<T> getDelegate();
}
