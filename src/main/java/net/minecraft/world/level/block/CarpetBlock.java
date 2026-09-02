package net.minecraft.world.level.block;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class CarpetBlock extends Block {
    public static final MapCodec<CarpetBlock> CODEC = simpleCodec(CarpetBlock::new);
    private static final VoxelShape SHAPE = Block.column(16.0, 0.0, 1.0);
    // MODIFIED for porting: was VFP block/shape MixinCarpetBlock#viaFabricPlus$shape_r1_7_10 (@Unique constant)
    // The -0.00001 lower bound is upstream's: it keeps the zero-height plate from collapsing into an empty shape.
    private static final VoxelShape vfpShapeR1_7_10 = Block.box(0.0, -0.00001 /* 0.0 */, 0.0, 16.0, 0.0, 16.0);

    @Override
    public MapCodec<? extends CarpetBlock> codec() {
        return CODEC;
    }

    public CarpetBlock(final BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return SHAPE;
    }

    // MODIFIED for porting: was VFP block/shape MixinCarpetBlock#getCollisionShape (@Override, added method)
    // <= 1.7.6 gave a carpet a flat zero-height collision plane instead of the modern 1px slab. Collision only - the
    // outline shape above stays vanilla.
    @Override
    protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_7_6)) {
            return vfpShapeR1_7_10;
        } else {
            return super.getCollisionShape(state, level, pos, context);
        }
    }

    @Override
    protected BlockState updateShape(
        final BlockState state,
        final LevelReader level,
        final ScheduledTickAccess ticks,
        final BlockPos pos,
        final Direction directionToNeighbour,
        final BlockPos neighbourPos,
        final BlockState neighbourState,
        final RandomSource random
    ) {
        return !state.canSurvive(level, pos)
            ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    @Override
    protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
        return !level.isEmptyBlock(pos.below());
    }
}