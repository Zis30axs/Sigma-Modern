package net.irisshaders.iris.mixin.statelisteners;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface BooleanStateAccessor {
	boolean isEnabled();
}
