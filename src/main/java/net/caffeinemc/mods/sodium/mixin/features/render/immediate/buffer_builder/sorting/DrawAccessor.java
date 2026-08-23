package net.caffeinemc.mods.sodium.mixin.features.render.immediate.buffer_builder.sorting;

import com.mojang.blaze3d.vertex.VertexSorting;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface DrawAccessor {
    VertexSorting getQuadSorting();
}
