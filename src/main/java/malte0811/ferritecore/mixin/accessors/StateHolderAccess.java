package malte0811.ferritecore.mixin.accessors;

import net.minecraft.world.level.block.state.properties.Property;

/**
 * Was an accessor Mixin on {@link net.minecraft.world.level.block.state.StateHolder} upstream. In this source-level port
 * the vanilla class implements this interface directly.
 */
public interface StateHolderAccess {
    Property<?>[] getPropertyKeys();
}
