package net.caffeinemc.mods.sodium.mixin.core;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.pipeline.RenderPipeline;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface GlCommandEncoderAccessor {
    void sodium$applyPipelineState(RenderPipeline pipeline);

    void sodium$setLastProgram(GlProgram program);
}
