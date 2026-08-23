package net.caffeinemc.mods.sodium.client.services.vanilla;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.function.Function;
import net.caffeinemc.mods.sodium.client.services.PlatformLevelRenderHooks;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

/**
 * MODIFIED for porting: replaces the loader specific {@code FabricLevelRenderHooks} /
 * {@code NeoForgeLevelRenderHooks}. All three methods exist purely to dispatch loader render events / NeoForge chunk mesh
 * appenders; without a loader there are none, which is exactly what the Fabric implementation does as well.
 */
public class VanillaLevelRenderHooks implements PlatformLevelRenderHooks {
    @Override
    public void runChunkLayerEvents(
        final RenderType renderLayer,
        final Level level,
        final LevelRenderer levelRenderer,
        final Matrix4f modelMatrix,
        final Matrix4f projectionMatrix,
        final int ticks,
        final Camera mainCamera,
        final Frustum cullingFrustum
    ) {
    }

    @Override
    public List<?> retrieveChunkMeshAppenders(final Level level, final BlockPos origin) {
        return List.of();
    }

    @Override
    public void runChunkMeshAppenders(
        final List<?> renderers, final Function<ChunkSectionLayer, VertexConsumer> typeToConsumer, final LevelSlice slice, final BlockPos origin
    ) {
    }
}
