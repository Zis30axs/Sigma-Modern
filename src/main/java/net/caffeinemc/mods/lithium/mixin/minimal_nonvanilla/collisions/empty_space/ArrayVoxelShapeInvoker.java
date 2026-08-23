package net.caffeinemc.mods.lithium.mixin.minimal_nonvanilla.collisions.empty_space;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;

/**
 * MODIFIED for porting: was a Mixin @Invoker for ArrayVoxelShape's package-private constructor. The constructor is now
 * public, so this simply calls it.
 */
public interface ArrayVoxelShapeInvoker {
    static ArrayVoxelShape init(DiscreteVoxelShape shape, DoubleList xPoints, DoubleList yPoints, DoubleList zPoints) {
        return new ArrayVoxelShape(shape, xPoints, yPoints, zPoints);
    }
}
