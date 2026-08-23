package malte0811.ferritecore.mixin.accessors;

/**
 * Was an accessor Mixin on {@link net.minecraft.world.phys.shapes.DiscreteVoxelShape} upstream. In this source-level
 * port the vanilla class implements this interface directly; the vanilla class already exposes all three getters.
 */
public interface DiscreteVSAccess {
    int getXSize();

    int getYSize();

    int getZSize();
}
