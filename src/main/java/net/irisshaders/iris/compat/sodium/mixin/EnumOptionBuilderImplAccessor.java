package net.irisshaders.iris.compat.sodium.mixin;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface EnumOptionBuilderImplAccessor<E extends Enum<E>> {
	Class<E> getEnumClass();
}
