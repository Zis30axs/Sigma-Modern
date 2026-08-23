package net.caffeinemc.mods.sodium.mixin.core;

import com.mojang.blaze3d.systems.RenderPassBackend;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface RenderPassAccessor {
    RenderPassBackend getBackend();
}
