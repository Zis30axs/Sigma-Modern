package net.minecraft.world.level.block.piston;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.mojang.serialization.MapCodec;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class PistonHeadBlock extends DirectionalBlock {
    public static final MapCodec<PistonHeadBlock> CODEC = simpleCodec(PistonHeadBlock::new);
    public static final EnumProperty<PistonType> TYPE = BlockStateProperties.PISTON_TYPE;
    public static final BooleanProperty SHORT = BlockStateProperties.SHORT;
    public static final int PLATFORM_THICKNESS = 4;
    private static final VoxelShape SHAPE_PLATFORM = Block.boxZ(16.0, 0.0, 4.0);
    private static final Map<Direction, VoxelShape> SHAPES_SHORT = Shapes.rotateAll(Shapes.or(SHAPE_PLATFORM, Block.boxZ(4.0, 4.0, 16.0)));
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateAll(Shapes.or(SHAPE_PLATFORM, Block.boxZ(4.0, 4.0, 20.0)));
    // MODIFIED for porting: was VFP block/shape MixinPistonHeadBlock#viaFabricPlus$<direction>_head_shape
    // (@Unique constants) - the bare 4-thick head plate per facing, without the 1.13+ arm stub.
    private static final VoxelShape vfpEastHeadShape = Block.box(12.0, 0.0, 0.0, 16.0, 16.0, 16.0);
    private static final VoxelShape vfpWestHeadShape = Block.box(0.0, 0.0, 0.0, 4.0, 16.0, 16.0);
    private static final VoxelShape vfpSouthHeadShape = Block.box(0.0, 0.0, 12.0, 16.0, 16.0, 16.0);
    private static final VoxelShape vfpNorthHeadShape = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 4.0);
    private static final VoxelShape vfpUpHeadShape = Block.box(0.0, 12.0, 0.0, 16.0, 16.0, 16.0);
    private static final VoxelShape vfpDownHeadShape = Block.box(0.0, 0.0, 0.0, 16.0, 4.0, 16.0);
    // MODIFIED for porting: was VFP block/shape MixinPistonHeadBlock#viaFabricPlus$<direction>_arm_shape_r1_8_x
    // (@Unique constants) - the 1.8.x arm stubs that only ever showed up in the collision box.
    private static final VoxelShape vfpUpArmShapeR1_8X = Block.box(6.0, 0.0, 6.0, 10.0, 12.0, 10.0);
    private static final VoxelShape vfpDownArmShapeR1_8X = Block.box(6.0, 4.0, 6.0, 10.0, 16.0, 10.0);
    private static final VoxelShape vfpSouthArmShapeR1_8X = Block.box(4.0, 6.0, 0.0, 12.0, 10.0, 12.0);
    private static final VoxelShape vfpNorthArmShapeR1_8X = Block.box(4.0, 6.0, 4.0, 12.0, 10.0, 16.0);
    private static final VoxelShape vfpEastArmShapeR1_8X = Block.box(0.0, 6.0, 4.0, 12.0, 10.0, 12.0);
    private static final VoxelShape vfpWestArmShapeR1_8X = Block.box(6.0, 4.0, 4.0, 10.0, 12.0, 16.0);
    // MODIFIED for porting: was VFP block/shape MixinPistonHeadBlock#viaFabricPlus$selfInflicted (@Unique field)
    // Re-entrancy flag: the <= 1.12.2 collision branch calls getShape and has to see the unmodified 1.13+ outline.
    private boolean vfpSelfInflicted;

    @Override
    protected MapCodec<PistonHeadBlock> codec() {
        return CODEC;
    }

    public PistonHeadBlock(final BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TYPE, PistonType.DEFAULT).setValue(SHORT, false));
    }

    @Override
    protected boolean useShapeForLightOcclusion(final BlockState state) {
        return true;
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        // MODIFIED for porting: was VFP block/shape MixinPistonHeadBlock#changeOutlineShape (@Inject HEAD, cancellable)
        // <= 1.12.2 had no outline for the arm at all, only the head plate. When the flag is set we are being called
        // back from the collision hook below, which wants the untouched 1.13+ head + arm union.
        if (this.vfpSelfInflicted) {
            this.vfpSelfInflicted = false;
        } else if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            return switch (state.getValue(FACING)) {
                case DOWN -> vfpDownHeadShape;
                case UP -> vfpUpHeadShape;
                case NORTH -> vfpNorthHeadShape;
                case SOUTH -> vfpSouthHeadShape;
                case WEST -> vfpWestHeadShape;
                case EAST -> vfpEastHeadShape;
            };
        }

        return (state.getValue(SHORT) ? SHAPES_SHORT : SHAPES).get(state.getValue(FACING));
    }

    // MODIFIED for porting: was VFP block/shape MixinPistonHeadBlock#getCollisionShape (@Override, added method)
    // <= 1.8 collided against the head plate plus a thin 1.8-style arm stub; 1.9 through 1.12.2 collide against the
    // 1.13+ outline, which the flag above hands back to us.
    @Override
    protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            return switch (state.getValue(FACING)) {
                case DOWN -> Shapes.or(vfpDownHeadShape, vfpDownArmShapeR1_8X);
                case UP -> Shapes.or(vfpUpHeadShape, vfpUpArmShapeR1_8X);
                case NORTH -> Shapes.or(vfpNorthHeadShape, vfpNorthArmShapeR1_8X);
                case SOUTH -> Shapes.or(vfpSouthHeadShape, vfpSouthArmShapeR1_8X);
                case WEST -> Shapes.or(vfpWestHeadShape, vfpWestArmShapeR1_8X);
                case EAST -> Shapes.or(vfpEastHeadShape, vfpEastArmShapeR1_8X);
            };
        } else if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            this.vfpSelfInflicted = true;
            return this.getShape(state, level, pos, context);
        }

        return super.getCollisionShape(state, level, pos, context);
    }

    private boolean isFittingBase(final BlockState armState, final BlockState potentialBase) {
        Block baseBlock = armState.getValue(TYPE) == PistonType.DEFAULT ? Blocks.PISTON : Blocks.STICKY_PISTON;
        return potentialBase.is(baseBlock) && potentialBase.getValue(PistonBaseBlock.EXTENDED) && potentialBase.getValue(FACING) == armState.getValue(FACING);
    }

    @Override
    public BlockState playerWillDestroy(final Level level, final BlockPos pos, final BlockState state, final Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops()) {
            BlockPos basePos = pos.relative(state.getValue(FACING).getOpposite());
            if (this.isFittingBase(state, level.getBlockState(basePos))) {
                level.destroyBlock(basePos, false);
            }
        }

        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void affectNeighborsAfterRemoval(final BlockState state, final ServerLevel level, final BlockPos pos, final boolean movedByPiston) {
        BlockPos basePos = pos.relative(state.getValue(FACING).getOpposite());
        if (this.isFittingBase(state, level.getBlockState(basePos))) {
            level.destroyBlock(basePos, true);
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
        return directionToNeighbour.getOpposite() == state.getValue(FACING) && !state.canSurvive(level, pos)
            ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    @Override
    protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
        BlockState base = level.getBlockState(pos.relative(state.getValue(FACING).getOpposite()));
        return this.isFittingBase(state, base) || base.is(Blocks.MOVING_PISTON) && base.getValue(FACING) == state.getValue(FACING);
    }

    @Override
    protected void neighborChanged(
        final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston
    ) {
        if (state.canSurvive(level, pos)) {
            level.neighborChanged(
                pos.relative(state.getValue(FACING).getOpposite()),
                block,
                ExperimentalRedstoneUtils.withFront(orientation, state.getValue(FACING).getOpposite())
            );
        }
    }

    @Override
    public ItemStack getCloneItemStack(final LevelReader level, final BlockPos pos, final BlockState state, final boolean includeData) {
        return new ItemStack(state.getValue(TYPE) == PistonType.STICKY ? Blocks.STICKY_PISTON : Blocks.PISTON);
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
        builder.add(FACING, TYPE, SHORT);
    }

    @Override
    protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
        return false;
    }
}