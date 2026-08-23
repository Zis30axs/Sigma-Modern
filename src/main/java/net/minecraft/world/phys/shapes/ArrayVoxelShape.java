package net.minecraft.world.phys.shapes;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.Arrays;
import malte0811.ferritecore.mixin.accessors.ArrayVSAccess; // MODIFIED for porting
import net.minecraft.core.Direction;
import net.minecraft.util.Util;

public class ArrayVoxelShape extends VoxelShape implements ArrayVSAccess { // MODIFIED for porting
    // MODIFIED for porting: no longer final so that FerriteCore's blockstate cache deduplication can replace the point
    // lists of a duplicate shape with those of the shape it keeps.
    private DoubleList xs;
    private DoubleList ys;
    private DoubleList zs;

    ArrayVoxelShape(final DiscreteVoxelShape shape, final double[] xs, final double[] ys, final double[] zs) {
        this(
            shape,
            DoubleArrayList.wrap(Arrays.copyOf(xs, shape.getXSize() + 1)),
            DoubleArrayList.wrap(Arrays.copyOf(ys, shape.getYSize() + 1)),
            DoubleArrayList.wrap(Arrays.copyOf(zs, shape.getZSize() + 1))
        );
    }

    // MODIFIED for porting: lithium reached this constructor through a Mixin @Invoker, so it must be public
    public ArrayVoxelShape(final DiscreteVoxelShape shape, final DoubleList xs, final DoubleList ys, final DoubleList zs) {
        super(shape);
        int xSize = shape.getXSize() + 1;
        int ySize = shape.getYSize() + 1;
        int zSize = shape.getZSize() + 1;
        if (xSize == xs.size() && ySize == ys.size() && zSize == zs.size()) {
            this.xs = xs;
            this.ys = ys;
            this.zs = zs;
        } else {
            throw (IllegalArgumentException)Util.pauseInIde(
                new IllegalArgumentException("Lengths of point arrays must be consistent with the size of the VoxelShape.")
            );
        }
    }

    @Override
    public DoubleList getCoords(final Direction.Axis axis) {
        return switch (axis) {
            case X -> this.xs;
            case Y -> this.ys;
            case Z -> this.zs;
        };
    }

    // MODIFIED for porting: the following accessors were FerriteCore's ArrayVSAccess accessor Mixin
    @Override
    public void setXPoints(final DoubleList newPoints) {
        this.xs = newPoints;
    }

    @Override
    public void setYPoints(final DoubleList newPoints) {
        this.ys = newPoints;
    }

    @Override
    public void setZPoints(final DoubleList newPoints) {
        this.zs = newPoints;
    }

    @Override
    public DoubleList getXPoints() {
        return this.xs;
    }

    @Override
    public DoubleList getYPoints() {
        return this.ys;
    }

    @Override
    public DoubleList getZPoints() {
        return this.zs;
    }
}