package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
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
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class LanternBlock extends Block implements SimpleWaterloggedBlock {
    public static final MapCodec<LanternBlock> CODEC = simpleCodec(LanternBlock::new);
    public static final BooleanProperty HANGING = BlockStateProperties.HANGING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final VoxelShape SHAPE_STANDING = Shapes.or(Block.column(4.0, 7.0, 9.0), Block.column(6.0, 0.0, 7.0));
    private static final VoxelShape SHAPE_HANGING = SHAPE_STANDING.move(0.0, 0.0625, 0.0).optimize();
    // MODIFIED for porting: was VFP bedrock/block MixinLanternBlock @Unique constants
    // (viaFabricPlus$shape_bedrock, viaFabricPlus$shape_hanging_bedrock)
    // Bedrock lanterns are a single 6/16 half-height box instead of vanilla's chain + body union.
    private static final VoxelShape vfpShapeBedrock = Shapes.box(0.3125, 0.0, 0.3125, 0.6875, 0.5, 0.6875);
    private static final VoxelShape vfpShapeHangingBedrock = Shapes.box(0.3125, 0.125, 0.3125, 0.6875, 0.625, 0.6875);

    @Override
    public MapCodec<? extends LanternBlock> codec() {
        return CODEC;
    }

    public LanternBlock(final BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(HANGING, false).setValue(WATERLOGGED, false));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
        FluidState replacedFluidState = context.getLevel().getFluidState(context.getClickedPos());

        for (Direction direction : context.getNearestLookingDirections()) {
            if (direction.getAxis() == Direction.Axis.Y) {
                BlockState state = this.defaultBlockState().setValue(HANGING, direction == Direction.UP);
                if (state.canSurvive(context.getLevel(), context.getClickedPos())) {
                    return state.setValue(WATERLOGGED, replacedFluidState.is(Fluids.WATER));
                }
            }
        }

        return null;
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        // MODIFIED for porting: was VFP bedrock/block MixinLanternBlock#modifyCollisionShape (@Inject RETURN, cancellable)
        if (ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
            return state.getValue(HANGING) ? vfpShapeHangingBedrock : vfpShapeBedrock;
        }

        return state.getValue(HANGING) ? SHAPE_HANGING : SHAPE_STANDING;
    }

    // MODIFIED for porting: was VFP bedrock/block MixinLanternBlock#getOcclusionShape (@Override, added method)
    // The inherited occlusion shape is derived from getShape, so keep the vanilla silhouette here while the
    // Bedrock box above only affects outline and collision.
    @Override
    protected VoxelShape getOcclusionShape(final BlockState state) {
        if (ProtocolTranslator.getTargetVersion().equals(BedrockProtocolVersion.bedrockLatest)) {
            return state.getValue(HANGING) ? SHAPE_HANGING : SHAPE_STANDING;
        } else {
            return super.getOcclusionShape(state);
        }
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HANGING, WATERLOGGED);
    }

    @Override
    protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
        Direction direction = getConnectedDirection(state).getOpposite();
        return Block.canSupportCenter(level, pos.relative(direction), direction.getOpposite());
    }

    protected static Direction getConnectedDirection(final BlockState state) {
        return state.getValue(HANGING) ? Direction.DOWN : Direction.UP;
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
        if (state.getValue(WATERLOGGED)) {
            ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }

        return getConnectedDirection(state).getOpposite() == directionToNeighbour && !state.canSurvive(level, pos)
            ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    @Override
    protected FluidState getFluidState(final BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
        return false;
    }
}