package malte0811.ferritecore.mixin.accessors;

import net.minecraft.world.phys.shapes.DiscreteVoxelShape;

/**
 * Was an accessor Mixin on {@link net.minecraft.world.phys.shapes.SubShape} upstream. In this source-level port the
 * vanilla class implements this interface directly.
 */
public interface SubShapeAccess extends DiscreteVSAccess {
    DiscreteVoxelShape getParent();

    int getStartX();

    int getStartY();

    int getStartZ();

    int getEndX();

    int getEndY();

    int getEndZ();
}
