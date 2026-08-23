package malte0811.ferritecore.mixin.accessors;

import it.unimi.dsi.fastutil.doubles.DoubleList;

/**
 * Was an accessor Mixin on {@link net.minecraft.world.phys.shapes.ArrayVoxelShape} upstream. In this source-level port
 * the vanilla class implements this interface directly.
 */
public interface ArrayVSAccess extends VoxelShapeAccess {
    void setXPoints(DoubleList newPoints);

    void setYPoints(DoubleList newPoints);

    void setZPoints(DoubleList newPoints);

    DoubleList getXPoints();

    DoubleList getYPoints();

    DoubleList getZPoints();
}
