package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import java.util.function.Function;
import com.viaversion.viafabricplus.features.block.interaction.Block1_14;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class FenceBlock extends CrossCollisionBlock {
    public static final MapCodec<FenceBlock> CODEC = simpleCodec(FenceBlock::new);
    // MODIFIED for porting: was VFP block/shape MixinFenceBlock#viaFabricPlus$outline_shape_r1_12_2 and
    // viaFabricPlus$shape_b1_8_1 (@Unique constants) - the 1.12.2 outline table (a 0.375..0.625 post with the
    // connected arms running all the way to the block edge, full height) and the b1.8.1 24-high collision cube.
    private static final VoxelShape[] vfpOutlineShapeR1_12_2 = new VoxelShape[]{
        Shapes.box(0.375, 0.0, 0.375, 0.625, 1.0, 0.625),
        Shapes.box(0.375, 0.0, 0.375, 0.625, 1.0, 1.0),
        Shapes.box(0.0, 0.0, 0.375, 0.625, 1.0, 0.625),
        Shapes.box(0.0, 0.0, 0.375, 0.625, 1.0, 1.0),
        Shapes.box(0.375, 0.0, 0.0, 0.625, 1.0, 0.625),
        Shapes.box(0.375, 0.0, 0.0, 0.625, 1.0, 1.0),
        Shapes.box(0.0, 0.0, 0.0, 0.625, 1.0, 0.625),
        Shapes.box(0.0, 0.0, 0.0, 0.625, 1.0, 1.0),
        Shapes.box(0.375, 0.0, 0.375, 1.0, 1.0, 0.625),
        Shapes.box(0.375, 0.0, 0.375, 1.0, 1.0, 1.0),
        Shapes.box(0.0, 0.0, 0.375, 1.0, 1.0, 0.625),
        Shapes.box(0.0, 0.0, 0.375, 1.0, 1.0, 1.0),
        Shapes.box(0.375, 0.0, 0.0, 1.0, 1.0, 0.625),
        Shapes.box(0.375, 0.0, 0.0, 1.0, 1.0, 1.0),
        Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 0.625),
        Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
    };
    private static final VoxelShape vfpShapeB1_8_1 = Block.box(0.0, 0.0, 0.0, 16.0, 24.0, 16.0);
    private final Function<BlockState, VoxelShape> occlusionShapes;
    // MODIFIED for porting: was VFP block/shape MixinFenceBlock @Unique state
    // (viaFabricPlus$collision_shape_r1_4_7, viaFabricPlus$outline_shape_r1_4_7), filled by the constructor below.
    private final VoxelShape[] vfpCollisionShapeR1_4_7;
    private final VoxelShape[] vfpOutlineShapeR1_4_7;

    @Override
    public MapCodec<FenceBlock> codec() {
        return CODEC;
    }

    public FenceBlock(final BlockBehaviour.Properties properties) {
        super(4.0F, 16.0F, 4.0F, 16.0F, 24.0F, properties);
        this.registerDefaultState(
            this.stateDefinition.any().setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false).setValue(WATERLOGGED, false)
        );
        this.occlusionShapes = this.makeShapes(4.0F, 16.0F, 2.0F, 6.0F, 15.0F);
        // MODIFIED for porting: was VFP block/shape MixinFenceBlock#init1_4_7Shapes (@Inject <init> RETURN)
        this.vfpCollisionShapeR1_4_7 = this.vfpCreateShapes1_4_7(24.0F);
        this.vfpOutlineShapeR1_4_7 = this.vfpCreateShapes1_4_7(16.0F);
    }

    // MODIFIED for porting: was VFP block/shape MixinFenceBlock#viaFabricPlus$createShapes1_4_7 (@Unique helper)
    // The 1.4.7 fence lattice: a 6..10 post of the given height OR-ed with one of 16 per-side-mask boxes.
    private VoxelShape[] vfpCreateShapes1_4_7(final float height) {
        final float f = 6.0F;
        final float g = 10.0F;
        final float h = 6.0F;
        final float i = 10.0F;
        final VoxelShape baseShape = Block.box(f, 0.0, f, g, height, g);
        final VoxelShape northShape = Block.box(h, 0.0, 0.0, i, height, i);
        final VoxelShape southShape = Block.box(h, 0.0, h, i, height, 16.0);
        final VoxelShape westShape = Block.box(0.0, 0.0, h, i, height, i);
        final VoxelShape eastShape = Block.box(h, 0.0, h, 16.0, height, i);
        final VoxelShape[] voxelShapes = new VoxelShape[]{
            Shapes.empty(),
            Block.box(f, 0.0, h, g, height, 16.0),
            Block.box(0.0, 0.0, f, i, height, g),
            Block.box(f - 6, 0.0, h, g, height, 16.0),
            Block.box(f, 0.0, 0.0, g, height, i),
            Shapes.or(southShape, northShape),
            Block.box(f - 6, 0.0, 0.0, g, height, i),
            Block.box(f - 6, 0.0, h - 5, g, height, 16.0),
            Block.box(h, 0.0, f, 16.0, height, g),
            Block.box(h, 0.0, f, 16.0, height, g + 6),
            Shapes.or(westShape, eastShape),
            Block.box(h - 5, 0.0, f, 16.0, height, g + 6),
            Block.box(f, 0.0, 0.0, g + 6, height, i),
            Block.box(f, 0.0, 0.0, g + 6, height, i + 5),
            Block.box(h - 5, 0.0, f - 6, 16.0, height, g),
            Block.box(0.0, 0.0, 0.0, 16.0, height, 16.0)
        };

        for (int j = 0; j < 16; ++j) {
            voxelShapes[j] = Shapes.or(baseShape, voxelShapes[j]);
        }

        return voxelShapes;
    }

    // MODIFIED for porting: was VFP block/shape MixinFenceBlock#getShape (@Override, added method)
    // b1.8.1 and older drew a fence as a full cube, 1.4.7 and older used the wide 1.4.7 lattice and everything up to
    // 1.12.2 used the 0.375..0.625 post whose arms reach the block edge.
    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(LegacyProtocolVersion.b1_8tob1_8_1)) {
            return Shapes.block();
        } else if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(LegacyProtocolVersion.r1_4_6tor1_4_7)) {
            return this.vfpOutlineShapeR1_4_7[this.viaFabricPlus$getShapeIndex(state)];
        } else if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            return vfpOutlineShapeR1_12_2[this.viaFabricPlus$getShapeIndex(state)];
        } else {
            return super.getShape(state, level, pos, context);
        }
    }

    // MODIFIED for porting: was VFP block/shape MixinFenceBlock#getCollisionShape (@Override, added method)
    // b1.8.1 and older collided against a full 24-high cube; 1.4.7 and older against the 1.4.7 lattice built 24 high.
    @Override
    protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(LegacyProtocolVersion.b1_8tob1_8_1)) {
            return vfpShapeB1_8_1;
        } else if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(LegacyProtocolVersion.r1_4_6tor1_4_7)) {
            return this.vfpCollisionShapeR1_4_7[this.viaFabricPlus$getShapeIndex(state)];
        } else {
            return super.getCollisionShape(state, level, pos, context);
        }
    }

    @Override
    protected VoxelShape getOcclusionShape(final BlockState state) {
        return this.occlusionShapes.apply(state);
    }

    @Override
    protected VoxelShape getVisualShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return this.getShape(state, level, pos, context);
    }

    @Override
    protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
        return false;
    }

    public boolean connectsTo(final BlockState state, final boolean faceSolid, final Direction direction) {
        Block block = state.getBlock();
        boolean sameFence = this.isSameFence(state);
        boolean gate = block instanceof FenceGateBlock && FenceGateBlock.connectsToDirection(state, direction);
        // MODIFIED for porting: was VFP block/shape MixinFenceBlock#canConnect1_14 (@Inject RETURN, cancellable)
        // <= 1.14 a fence only ever attached to the blocks the piston attachment rules treat as exceptions, so every
        // other neighbour stays disconnected no matter what the vanilla rule says.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_14) && !Block1_14.isExceptBlockForAttachWithPiston(block)) {
            return false;
        }

        return !isExceptionForConnection(state) && faceSolid || sameFence || gate;
    }

    private boolean isSameFence(final BlockState state) {
        return state.is(BlockTags.FENCES) && state.is(BlockTags.WOODEN_FENCES) == this.defaultBlockState().is(BlockTags.WOODEN_FENCES);
    }

    @Override
    protected InteractionResult useWithoutItem(
        final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult
    ) {
        return !level.isClientSide() ? LeadItem.bindPlayerMobs(player, level, pos) : InteractionResult.PASS;
    }

    // MODIFIED for porting: was VFP block/interaction MixinFenceBlock#useItemOn (@Override, added method)
    // <= 1.21 the client decided lead binding itself: a lead in hand swings the arm, anything else falls through to
    // the normal use path. The <= 1.10 branch is upstream's and is unreachable - 1.10 is also <= 1.21 - kept in
    // upstream's order so the behaviour matches, which for every target <= 1.21 is LEAD -> SUCCESS, else PASS.
    @Override
    protected InteractionResult useItemOn(
        final ItemStack itemStack,
        final BlockState state,
        final Level level,
        final BlockPos pos,
        final Player player,
        final InteractionHand hand,
        final BlockHitResult hitResult
    ) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_21)) {
            return itemStack.is(Items.LEAD) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        } else if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_10)) {
            return InteractionResult.SUCCESS;
        } else {
            return super.useItemOn(itemStack, state, level, pos, player, hand, hitResult);
        }
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        BlockGetter level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        FluidState replacedFluidState = context.getLevel().getFluidState(context.getClickedPos());
        BlockPos north = pos.north();
        BlockPos east = pos.east();
        BlockPos south = pos.south();
        BlockPos west = pos.west();
        BlockState northState = level.getBlockState(north);
        BlockState eastState = level.getBlockState(east);
        BlockState southState = level.getBlockState(south);
        BlockState westState = level.getBlockState(west);
        return super.getStateForPlacement(context)
            .setValue(NORTH, this.connectsTo(northState, northState.isFaceSturdy(level, north, Direction.SOUTH), Direction.SOUTH))
            .setValue(EAST, this.connectsTo(eastState, eastState.isFaceSturdy(level, east, Direction.WEST), Direction.WEST))
            .setValue(SOUTH, this.connectsTo(southState, southState.isFaceSturdy(level, south, Direction.NORTH), Direction.NORTH))
            .setValue(WEST, this.connectsTo(westState, westState.isFaceSturdy(level, west, Direction.EAST), Direction.EAST))
            .setValue(WATERLOGGED, replacedFluidState.is(Fluids.WATER));
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

        return directionToNeighbour.getAxis().isHorizontal()
            ? state.setValue(
                PROPERTY_BY_DIRECTION.get(directionToNeighbour),
                this.connectsTo(
                    neighbourState, neighbourState.isFaceSturdy(level, neighbourPos, directionToNeighbour.getOpposite()), directionToNeighbour.getOpposite()
                )
            )
            : super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, WEST, SOUTH, WATERLOGGED);
    }
}