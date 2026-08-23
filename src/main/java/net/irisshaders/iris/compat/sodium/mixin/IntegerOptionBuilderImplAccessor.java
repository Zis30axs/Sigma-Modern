package net.irisshaders.iris.compat.sodium.mixin;

import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface IntegerOptionBuilderImplAccessor {
	ControlValueFormatter iris$getValueFormatter();
}
