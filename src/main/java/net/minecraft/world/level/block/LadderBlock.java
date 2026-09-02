package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import java.util.Map;
import com.viaversion.viafabricplus.features.block.interaction.Block1_14;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class LadderBlock extends Block implements SimpleWaterloggedBlock {
    public static final MapCodec<LadderBlock> CODEC = simpleCodec(LadderBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Block.boxZ(16.0, 13.0, 16.0));
    // MODIFIED for porting: was VFP block/shape MixinLadderBlock#viaFabricPlus$shapes_r1_8_x and
    // viaFabricPlus$shapes_bedrock (@Unique constants) - the per-facing ladder boxes those targets use, 2px deep
    // for <= 1.8 and the explicitly written 3px boxes Bedrock uses.
    private static final Map<Direction, VoxelShape> vfpShapesR1_8X = Map.of(
        Direction.NORTH, Block.box(0.0, 0.0, 14.0, 16.0, 16.0, 16.0),
        Direction.SOUTH, Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 2.0),
        Direction.WEST, Block.box(14.0, 0.0, 0.0, 16.0, 16.0, 16.0),
        Direction.EAST, Block.box(0.0, 0.0, 0.0, 2.0, 16.0, 16.0)
    );
    private static final Map<Direction, VoxelShape> vfpShapesBedrock = Map.of(
        Direction.NORTH, Shapes.box(0.0, 0.0, 0.8125, 1.0, 1.0, 1.0),
        Direction.SOUTH, Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 0.1875),
        Direction.WEST, Shapes.box(0.8125, 0.0, 0.0, 1.0, 1.0, 1.0),
        Direction.EAST, Shapes.box(0.0, 0.0, 0.0, 0.1875, 1.0, 1.0)
    );

    @Override
    public MapCodec<LadderBlock> codec() {
        return CODEC;
    }

    protected LadderBlock(final BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(WATERLOGGED, false));
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        // MODIFIED for porting: was VFP block/shape MixinLadderBlock#changeOutlineShape (@Redirect on the GETSTATIC
        // read of SHAPES) - the redirect swaps the whole per-facing map the lookup below indexes, so <= 1.8 gets the
        // 2px-deep ladder and Bedrock its own table.
        final Map<Direction, VoxelShape> shapes;
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            shapes = vfpShapesR1_8X;
        } else if (ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
            shapes = vfpShapesBedrock;
        } else {
            shapes = SHAPES;
        }

        return shapes.get(state.getValue(FACING));
    }

    // MODIFIED for porting: was VFP block/shape MixinLadderBlock#getOcclusionShape (@Override, added method)
    // On <= 1.8 and Bedrock this deliberately keeps the VANILLA map, so light occlusion is unaffected by the legacy
    // outline map getShape hands out above.
    @Override
    protected VoxelShape getOcclusionShape(final BlockState state) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)
            || ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
            return SHAPES.get(state.getValue(FACING));
        } else {
            return super.getOcclusionShape(state);
        }
    }

    private boolean canAttachTo(final BlockGetter level, final BlockPos pos, final Direction direction) {
        BlockState blockState = level.getBlockState(pos);
        return blockState.isFaceSturdy(level, pos, direction);
    }

    @Override
    protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
        Direction direction = state.getValue(FACING);
        final boolean canSurvive = this.canAttachTo(level, pos.relative(direction.getOpposite()), direction);
        // MODIFIED for porting: was VFP block/interaction MixinCanPlaceAt1_14#canPlaceAt1_14 (@Inject RETURN, cancellable)
        // <= 1.14 blocks which the piston attachment rules treat as exceptions could not be attached to at all, so the
        // vanilla result is vetoed. Upstream deliberately queries the block AT pos, not the block being attached to.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_14)
            && Block1_14.isExceptBlockForAttachWithPiston(level.getBlockState(pos).getBlock())) {
            return false;
        }

        return canSurvive;
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
        if (directionToNeighbour.getOpposite() == state.getValue(FACING) && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }

        if (state.getValue(WATERLOGGED)) {
            ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }

        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
        if (!context.replacingClickedOnBlock()) {
            BlockState state = context.getLevel().getBlockState(context.getClickedPos().relative(context.getClickedFace().getOpposite()));
            if (state.is(this) && state.getValue(FACING) == context.getClickedFace()) {
                return null;
            }
        }

        BlockState state = this.defaultBlockState();
        LevelReader level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        FluidState replacedFluidState = context.getLevel().getFluidState(context.getClickedPos());

        for (Direction direction : context.getNearestLookingDirections()) {
            if (direction.getAxis().isHorizontal()) {
                state = state.setValue(FACING, direction.getOpposite());
                if (state.canSurvive(level, pos)) {
                    return state.setValue(WATERLOGGED, replacedFluidState.is(Fluids.WATER));
                }
            }
        }

        return null;
    }

    @Override
    protected BlockState rotate(final BlockState state, final Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(final BlockState state, final Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, WATERLOGGED);
    }

    @Override
    protected FluidState getFluidState(final BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }
}