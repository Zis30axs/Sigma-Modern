package net.irisshaders.iris.mixin;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface EndFlashAccess {
	void setYAngle(float yAngle);

	void setXAngle(float xAngle);
}
