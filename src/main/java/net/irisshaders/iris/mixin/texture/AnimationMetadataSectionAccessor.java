package net.irisshaders.iris.mixin.texture;

import java.util.Optional;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface AnimationMetadataSectionAccessor {
	Optional<Integer> getFrameWidth();

	void setFrameWidth(Optional<Integer> frameWidth);

	Optional<Integer> getFrameHeight();

	void setFrameHeight(Optional<Integer> frameHeight);
}
