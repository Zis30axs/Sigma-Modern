package net.caffeinemc.mods.sodium.client.services.vanilla;

import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.render.model.AmbientOcclusionMode;
import net.caffeinemc.mods.sodium.client.services.PlatformBlockAccess;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * MODIFIED for porting: replaces the loader specific {@code FabricBlockAccess} / {@code NeoForgeBlockAccess}. The
 * {@code normalShade} code below is copied verbatim from the Fabric implementation (which took it from Indigo); the only
 * behavioural differences to that implementation are:
 * <ul>
 *   <li>{@code shouldShowFluidOverlay} uses vanilla's own condition from
 *       {@link net.minecraft.client.renderer.block.FluidRenderer} ({@code HalfTransparentBlock} or {@code LeavesBlock})
 *       instead of Fabric API's {@code FluidRenderingRegistry#isBlockTransparent}, whose default is that same check;</li>
 *   <li>{@code platformHasBlockData} returns false, because vanilla block entities have no extra render data.</li>
 * </ul>
 */
public class VanillaBlockAccess implements PlatformBlockAccess {
    /**
     * Ported from Indigo.
     * Finds mean of per-face shading factors weighted by normal components.
     * Not how light actually works but the vanilla diffuse shading model is a hack to start with
     * and this gives reasonable results for non-cubic surfaces in a vanilla-style renderer.
     */
    private float normalShade(final BlockAndTintGetter blockView, final float normalX, final float normalY, final float normalZ, final boolean hasShade) {
        float sum = 0;
        float div = 0;

        if (normalX > 0) {
            sum += normalX * this.getShade(blockView, Direction.EAST, hasShade);
            div += normalX;
        } else if (normalX < 0) {
            sum += -normalX * this.getShade(blockView, Direction.WEST, hasShade);
            div -= normalX;
        }

        if (normalY > 0) {
            sum += normalY * this.getShade(blockView, Direction.UP, hasShade);
            div += normalY;
        } else if (normalY < 0) {
            sum += -normalY * this.getShade(blockView, Direction.DOWN, hasShade);
            div -= normalY;
        }

        if (normalZ > 0) {
            sum += normalZ * this.getShade(blockView, Direction.SOUTH, hasShade);
            div += normalZ;
        } else if (normalZ < 0) {
            sum += -normalZ * this.getShade(blockView, Direction.NORTH, hasShade);
            div -= normalZ;
        }

        return sum / div;
    }

    private float getShade(final BlockAndTintGetter blockView, final Direction direction, final boolean hasShade) {
        if (hasShade) {
            return blockView.cardinalLighting().byFace(direction);
        } else {
            return blockView.cardinalLighting().up();
        }
    }

    @Override
    public int getLightEmission(final BlockState state, final BlockAndTintGetter level, final BlockPos pos) {
        return state.getLightEmission();
    }

    @Override
    public boolean shouldSkipRender(
        final BlockGetter level,
        final BlockState selfState,
        final BlockState otherState,
        final BlockPos selfPos,
        final BlockPos otherPos,
        final Direction facing
    ) {
        return false;
    }

    @Override
    public boolean shouldShowFluidOverlay(final BlockState block, final BlockAndTintGetter level, final BlockPos pos, final FluidState fluidState) {
        Block adjacent = block.getBlock();
        return adjacent instanceof HalfTransparentBlock || adjacent instanceof LeavesBlock;
    }

    @Override
    public boolean platformHasBlockData() {
        return false;
    }

    @Override
    public float getNormalVectorShade(final ModelQuadView quad, final BlockAndTintGetter level, final boolean shade) {
        int normal = quad.getFaceNormal();
        return this.normalShade(level, NormI8.unpackX(normal), NormI8.unpackY(normal), NormI8.unpackZ(normal), shade);
    }

    @Override
    public AmbientOcclusionMode usesAmbientOcclusion(
        final BlockStateModelPart model,
        final BlockState state,
        final ChunkSectionLayer renderType,
        final BlockAndTintGetter level,
        final BlockPos pos
    ) {
        return model.useAmbientOcclusion() ? AmbientOcclusionMode.DEFAULT : AmbientOcclusionMode.DISABLED;
    }

    @Override
    public boolean shouldBlockEntityGlow(final BlockEntity blockEntity, final LocalPlayer player) {
        return false;
    }

    @Override
    public boolean shouldOccludeFluid(final Direction adjDirection, final BlockState adjBlockState, final FluidState fluid) {
        return adjBlockState.getFluidState().getType().isSame(fluid.getType());
    }
}
