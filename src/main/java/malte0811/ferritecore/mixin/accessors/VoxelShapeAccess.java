package malte0811.ferritecore.mixin.accessors;

import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Was an accessor Mixin on {@link net.minecraft.world.phys.shapes.VoxelShape} upstream. In this source-level port the
 * vanilla class implements this interface directly.
 */
public interface VoxelShapeAccess {
    DiscreteVoxelShape getShape();

    @Nullable
    VoxelShape[] getFaces();

    void setShape(DiscreteVoxelShape newPart);

    void setFaces(@Nullable VoxelShape[] newCache);
}
