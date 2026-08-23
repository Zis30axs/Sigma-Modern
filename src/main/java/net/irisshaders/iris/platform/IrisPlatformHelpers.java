package net.irisshaders.iris.platform;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;

public interface IrisPlatformHelpers {
	// MODIFIED for porting: the ServiceLoader indirection is gone - this project has no mod loader, so the vanilla
	// implementation is referenced directly.
	IrisPlatformHelpers INSTANCE = new VanillaIrisPlatformHelpers();

	static IrisPlatformHelpers getInstance() {
		return INSTANCE;
	}

	boolean isModLoaded(String modId);

	String getVersion();

	boolean isDevelopmentEnvironment();

	Path getGameDir();

	Path getConfigDir();

	int compareVersions(String currentVersion, String semanticVersion) throws Exception;

	KeyMapping registerKeyBinding(KeyMapping keyMapping);

	boolean useELS();

    BlockState getBlockAppearance(BlockAndTintGetter level, BlockState state, Direction cullFace, BlockPos pos);

}
