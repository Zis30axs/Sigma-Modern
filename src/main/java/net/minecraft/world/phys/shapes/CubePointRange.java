package net.minecraft.world.phys.shapes;

import it.unimi.dsi.fastutil.doubles.AbstractDoubleList;

public class CubePointRange extends AbstractDoubleList {
    private final int parts;
    // MODIFIED for porting: lithium shapes.precompute_shape_arrays CubePointRangeMixin precomputes the reciprocal so the
    // very hot getDouble can multiply instead of divide.
    private final double scale;

    public CubePointRange(final int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("Need at least 1 part");
        }

        this.parts = parts;
        this.scale = 1.0 / parts;
    }

    @Override
    public double getDouble(final int index) {
        // MODIFIED for porting: lithium shapes.precompute_shape_arrays CubePointRangeMixin
        return index * this.scale;
    }

    @Override
    public int size() {
        return this.parts + 1;
    }
}