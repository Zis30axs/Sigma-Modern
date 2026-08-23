package malte0811.ferritecore.mixin.accessors;

import java.util.BitSet;

/**
 * Was an accessor Mixin on {@link net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape} upstream. In this
 * source-level port the vanilla class implements this interface directly.
 */
public interface BitSetDVSAccess extends DiscreteVSAccess {
    BitSet getStorage();

    int getXMin();

    int getYMin();

    int getZMin();

    int getXMax();

    int getYMax();

    int getZMax();
}
