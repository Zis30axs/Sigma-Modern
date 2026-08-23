package me.flashyreese.mods.sodiumextra.mixin.panini_projection;

import com.mojang.blaze3d.buffers.GpuBuffer;
import java.util.Map;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface AccessorPostPass {
    Map<String, GpuBuffer> sodiumExtra$getCustomUniforms();
}
