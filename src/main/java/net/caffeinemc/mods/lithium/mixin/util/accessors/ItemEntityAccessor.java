package net.caffeinemc.mods.lithium.mixin.util.accessors;

import java.util.UUID;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface ItemEntityAccessor {
    UUID lithium$getOwner();
}
