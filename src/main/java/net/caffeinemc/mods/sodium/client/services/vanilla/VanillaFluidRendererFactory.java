package net.caffeinemc.mods.sodium.client.services.vanilla;

import java.util.Arrays;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.color.ColorProviderRegistry;
import net.caffeinemc.mods.sodium.client.model.light.LightPipelineProvider;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.blender.BlendedColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.helper.ColorHelper;
import net.caffeinemc.mods.sodium.client.services.FluidRendererFactory;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * MODIFIED for porting: replaces the loader specific {@code FluidRendererImpl}. Both loader implementations exist only to
 * route the fluid rendering through the loader's fluid render handler indirection (Fabric API's
 * {@code FluidRenderHandler}/{@code FluidRendering}, NeoForge's {@code IClientFluidTypeExtensions}) so that mods can override
 * it. Without a loader there is no such indirection, so this implementation goes straight to sodium's
 * {@link DefaultFluidRenderer} with vanilla's fluid model and its {@link BlockTintSource} - which is exactly what the Fabric
 * implementation ends up doing when no mod registered an override.
 */
public class VanillaFluidRendererFactory implements FluidRendererFactory {
    @Override
    public FluidRenderer createPlatformFluidRenderer(final ColorProviderRegistry colorRegistry, final LightPipelineProvider lightPipelineProvider) {
        return new VanillaFluidRenderer(colorRegistry, lightPipelineProvider);
    }

    @Override
    public BlendedColorProvider<FluidState> getWaterColorProvider() {
        return new BlendedColorProvider<>() {
            @Override
            protected int getColor(final LevelSlice slice, final FluidState state, final BlockPos pos) {
                return ColorHelper.makeOpaqueIfTransparent(BiomeColors.getAverageWaterColor(slice, pos));
            }
        };
    }

    @Override
    public BlendedColorProvider<BlockState> getWaterBlockColorProvider() {
        return new BlendedColorProvider<>() {
            @Override
            protected int getColor(final LevelSlice slice, final BlockState state, final BlockPos pos) {
                return ColorHelper.makeOpaqueIfTransparent(BiomeColors.getAverageWaterColor(slice, pos));
            }
        };
    }

    /**
     * MODIFIED for porting: the vanilla-only part of the loader specific {@code FluidRendererImpl}.
     */
    private static class VanillaFluidRenderer extends FluidRenderer {
        private final ColorProviderRegistry colorProviderRegistry;
        private final DefaultFluidRenderer defaultRenderer;
        private final FluidStateModelSet fluidStates;

        VanillaFluidRenderer(final ColorProviderRegistry colorProviderRegistry, final LightPipelineProvider lighters) {
            this.colorProviderRegistry = colorProviderRegistry;
            this.defaultRenderer = new DefaultFluidRenderer(lighters);
            this.fluidStates = Minecraft.getInstance().getModelManager().getFluidStateModelSet();
        }

        @Override
        public void render(
            final LevelSlice level,
            final BlockState blockState,
            final FluidState fluidState,
            final BlockPos blockPos,
            final BlockPos offset,
            final TranslucentGeometryCollector collector,
            final ChunkBuildBuffers buffers
        ) {
            var model = this.fluidStates.get(fluidState);
            var material = DefaultMaterials.forChunkLayer(model.layer());
            var meshBuilder = buffers.get(material);
            var colorProvider = this.colorProviderRegistry.getColorProvider(fluidState.getType());
            if (colorProvider == null) {
                colorProvider = adapt(model.tintSource());
            }

            this.defaultRenderer.render(level, blockState, fluidState, blockPos, offset, collector, meshBuilder, material, colorProvider, model);
        }
    }

    /**
     * MODIFIED for porting: was the loader specific {@code FabricColorProviders#adapt}, which only uses vanilla API.
     */
    private static ColorProvider<FluidState> adapt(final @Nullable BlockTintSource tintSource) {
        return new FluidTintSourceAdapter(tintSource);
    }

    private static class FluidTintSourceAdapter implements ColorProvider<FluidState> {
        private final @Nullable BlockTintSource tintSource;

        FluidTintSourceAdapter(final @Nullable BlockTintSource tintSource) {
            this.tintSource = tintSource;
        }

        @Override
        public void getColors(
            final LevelSlice slice,
            final BlockPos pos,
            final BlockPos.MutableBlockPos scratchPos,
            final FluidState state,
            final ModelQuadView quad,
            final int[] output,
            final boolean smooth
        ) {
            if (this.tintSource == null) {
                Arrays.fill(output, -1);
                return;
            }

            int color = ColorHelper.makeOpaqueIfTransparent(this.tintSource.colorInWorld(state.createLegacyBlock(), slice, pos));
            Arrays.fill(output, color);
        }
    }
}
