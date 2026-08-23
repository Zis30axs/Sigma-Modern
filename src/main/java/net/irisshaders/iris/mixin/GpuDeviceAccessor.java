package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.systems.GpuDeviceBackend;

/**
 * MODIFIED for porting: was a Mixin accessor interface; the vanilla class now implements it directly.
 */
public interface GpuDeviceAccessor {
	GpuDeviceBackend getBackend();
}
