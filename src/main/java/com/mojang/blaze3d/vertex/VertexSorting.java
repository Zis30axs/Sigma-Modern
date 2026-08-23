package com.mojang.blaze3d.vertex;

import com.google.common.primitives.Floats;
import it.unimi.dsi.fastutil.ints.IntArrays;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector3fc;

@OnlyIn(Dist.CLIENT)
public interface VertexSorting {
    VertexSorting DISTANCE_TO_ORIGIN = byDistance(0.0F, 0.0F, 0.0F);
    // MODIFIED for porting: sodium features.render.immediate.buffer_builder.sorting VertexSortingMixin#modifyVertexSorting
    // (@ModifyExpressionValue on the PUTSTATIC of this constant)
    VertexSorting ORTHOGRAPHIC_Z = net.caffeinemc.mods.sodium.client.util.sorting.VertexSorters.orthographicZ();

    /**
     * MODIFIED for porting: sodium features.render.immediate.buffer_builder.sorting VertexSortingMixin (@Overwrite) -
     * optimized vertex sorting.
     */
    static VertexSorting byDistance(final float x, final float y, final float z) {
        return net.caffeinemc.mods.sodium.client.util.sorting.VertexSorters.distance(x, y, z);
    }

    static VertexSorting byDistance(final Vector3fc origin) {
        return byDistance(origin.x(), origin.y(), origin.z());
    }

    /**
     * MODIFIED for porting: sodium features.render.immediate.buffer_builder.sorting VertexSortingMixin (@Overwrite) -
     * optimized vertex sorting.
     */
    static VertexSorting byDistance(final VertexSorting.DistanceFunction function) {
        return net.caffeinemc.mods.sodium.client.util.sorting.VertexSorters.fallback(function);
    }

    // MODIFIED for porting: original vanilla body of byDistance(DistanceFunction), replaced above
    static VertexSorting sodium$vanillaByDistance(final VertexSorting.DistanceFunction function) {
        return values -> {
            Vector3f scratch = new Vector3f();
            float[] keys = new float[values.size()];
            int[] indices = new int[values.size()];

            for (int i = 0; i < values.size(); indices[i] = i++) {
                keys[i] = function.apply(values.get(i, scratch));
            }

            IntArrays.mergeSort(indices, (o1, o2) -> Floats.compare(keys[o2], keys[o1]));
            return indices;
        };
    }

    int[] sort(CompactVectorArray points);

    @FunctionalInterface
    @OnlyIn(Dist.CLIENT)
    interface DistanceFunction {
        float apply(Vector3f value);
    }
}