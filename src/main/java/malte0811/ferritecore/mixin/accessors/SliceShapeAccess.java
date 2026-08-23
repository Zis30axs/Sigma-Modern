package malte0811.ferritecore.mixin.accessors;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Was an accessor Mixin on {@link net.minecraft.world.phys.shapes.SliceShape} upstream. In this source-level port the
 * vanilla class implements this interface directly.
 */
public interface SliceShapeAccess extends VoxelShapeAccess {
    VoxelShape getDelegate();

    Direction.Axis getAxis();
}
