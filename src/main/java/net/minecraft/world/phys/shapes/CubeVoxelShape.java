package net.minecraft.world.phys.shapes;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

public final class CubeVoxelShape extends VoxelShape {
    // MODIFIED for porting: lithium shapes.precompute_shape_arrays CubeVoxelShapeMixin builds the three coordinate lists
    // once instead of allocating a new CubePointRange on every getCoords call.
    private static final Direction.Axis[] LITHIUM_AXIS = Direction.Axis.values();
    private final DoubleList[] lithium$coords;

    public CubeVoxelShape(final DiscreteVoxelShape shape) {
        super(shape);
        this.lithium$coords = new DoubleList[LITHIUM_AXIS.length];

        for (Direction.Axis axis : LITHIUM_AXIS) {
            this.lithium$coords[axis.ordinal()] = new CubePointRange(shape.getSize(axis));
        }
    }

    @Override
    public DoubleList getCoords(final Direction.Axis axis) {
        // MODIFIED for porting: lithium shapes.precompute_shape_arrays CubeVoxelShapeMixin
        return this.lithium$coords[axis.ordinal()];
    }

    @Override
    public int findIndex(final Direction.Axis axis, final double coord) { // MODIFIED for porting: lithium.accesswidener widened access
        int size = this.shape.getSize(axis);
        return Mth.floor(Mth.clamp(coord * size, -1.0, size));
    }
}