package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viafabricplus.settings.impl.DebugSettings;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class IronBarsBlock extends CrossCollisionBlock {
    public static final MapCodec<IronBarsBlock> CODEC = simpleCodec(IronBarsBlock::new);
    // MODIFIED for porting: was VFP block/shape MixinIronBarsBlock @Unique state
    // (viaFabricPlus$shape_r1_12_2, viaFabricPlus$shape_r1_8), both filled by the constructor below.
    private final VoxelShape[] vfpShapeR1_12_2;
    private final VoxelShape[] vfpShapeR1_8;

    @Override
    public MapCodec<? extends IronBarsBlock> codec() {
        return CODEC;
    }

    protected IronBarsBlock(final BlockBehaviour.Properties properties) {
        super(2.0F, 16.0F, 2.0F, 16.0F, 16.0F, properties);
        this.registerDefaultState(
            this.stateDefinition.any().setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false).setValue(WATERLOGGED, false)
        );
        // MODIFIED for porting: was VFP block/shape MixinIronBarsBlock#initShapes1_8 (@Inject <init> RETURN)
        // Both legacy pane tables, indexed by the 4-bit N/E/S/W connection mask: 1.12.2 arms run to the block edge
        // and the cross case is a full block, 1.8 arms stop 1px short of the edge and are OR-ed per corner.
        final float f = 7.0F;
        final float g = 9.0F;
        final float h = 7.0F;
        final float i = 9.0F;

        final VoxelShape baseShape = Block.box(f, 0.0, f, g, 16.0, g);

        this.vfpShapeR1_12_2 = new VoxelShape[]{
            baseShape,
            Block.box(h, 0.0, h, i, 16.0, 16.0), // south
            Block.box(0.0, 0.0, h, i, 16.0, i), // west
            Block.box(0.0, 0.0, h, i, 16.0, 16.0), // south-west corner
            Block.box(h, 0.0, 0.0, i, 16.0, i), // north
            Block.box(h, 0.0, 0.0, i, 16.0, 16.0), // south-north line
            Block.box(0.0, 0.0, 0.0, i, 16.0, i), // west-north corner
            Block.box(0.0, 0.0, 0.0, i, 16.0, 16.0), // south-west-north T
            Block.box(h, 0.0, h, 16.0, 16.0, i), // east
            Block.box(h, 0.0, h, 16.0, 16.0, 16.0), // south-east corner
            Block.box(0.0, 0.0, h, 16.0, 16.0, i), // west-east line
            Block.box(0.0, 0.0, h, 16.0, 16.0, 16.0), // south-west-east T
            Block.box(h, 0.0, 0.0, 16.0, 16.0, i), // north-east corner
            Block.box(h, 0.0, 0.0, 16.0, 16.0, 16.0), // south-north-east T
            Block.box(0.0, 0.0, 0.0, 16.0, 16.0, i), // west-north-east T
            Shapes.block() // cross
        };

        final VoxelShape northShape = Block.box(h, 0.0, 0.0, i, 16.0, i - 1);
        final VoxelShape southShape = Block.box(h, 0.0, h + 1, i, 16.0, 16.0);
        final VoxelShape westShape = Block.box(0.0, 0.0, h, i - 1, 16.0, i);
        final VoxelShape eastShape = Block.box(h + 1, 0.0, h, 16.0, 16.0, i);

        final VoxelShape northEastCornerShape = Shapes.or(northShape, eastShape);
        final VoxelShape southWestCornerShape = Shapes.or(southShape, westShape);

        this.vfpShapeR1_8 = new VoxelShape[]{
            baseShape,
            southShape,
            westShape,
            southWestCornerShape,
            northShape,
            Shapes.or(southShape, northShape),
            Shapes.or(westShape, northShape),
            Shapes.or(southWestCornerShape, northShape),
            eastShape,
            Shapes.or(southShape, eastShape),
            Shapes.or(westShape, eastShape),
            Shapes.or(southWestCornerShape, eastShape),
            northEastCornerShape,
            Shapes.or(southShape, northEastCornerShape),
            Shapes.or(westShape, northEastCornerShape),
            Shapes.or(southWestCornerShape, northEastCornerShape)
        };
    }

    // MODIFIED for porting: was VFP block/shape MixinIronBarsBlock#getShape (@Override, added method)
    // Gated by the legacyPaneOutlines debug setting (its range is 1.12.2 and older), not by a raw version compare.
    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        if (DebugSettings.INSTANCE.legacyPaneOutlines.isEnabled()) {
            return this.vfpShapeR1_12_2[this.viaFabricPlus$getShapeIndex(state)];
        } else {
            return super.getShape(state, level, pos, context);
        }
    }

    // MODIFIED for porting: was VFP block/shape MixinIronBarsBlock#getCollisionShape (@Override, added method)
    // <= 1.8 panes collided against arms that stop 1px short of the block edge.
    @Override
    protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            return this.vfpShapeR1_8[this.viaFabricPlus$getShapeIndex(state)];
        } else {
            return super.getCollisionShape(state, level, pos, context);
        }
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        BlockGetter level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        FluidState replacedFluidState = context.getLevel().getFluidState(context.getClickedPos());
        BlockPos north = pos.north();
        BlockPos south = pos.south();
        BlockPos west = pos.west();
        BlockPos east = pos.east();
        BlockState northState = level.getBlockState(north);
        BlockState southState = level.getBlockState(south);
        BlockState westState = level.getBlockState(west);
        BlockState eastState = level.getBlockState(east);
        // MODIFIED for porting: was VFP block/interaction MixinIronBarsBlock#countConnections (@WrapOperation on every
        // attachsTo call in this method) - the wrap only counts how many sides attached, it never changes a result, so
        // the four calls are bound to locals here and counted below.
        final boolean attachNorth = this.attachsTo(northState, northState.isFaceSturdy(level, north, Direction.SOUTH));
        final boolean attachSouth = this.attachsTo(southState, southState.isFaceSturdy(level, south, Direction.NORTH));
        final boolean attachWest = this.attachsTo(westState, westState.isFaceSturdy(level, west, Direction.EAST));
        final boolean attachEast = this.attachsTo(eastState, eastState.isFaceSturdy(level, east, Direction.WEST));
        final int connections = (attachNorth ? 1 : 0) + (attachSouth ? 1 : 0) + (attachWest ? 1 : 0) + (attachEast ? 1 : 0);
        final BlockState placementState = this.defaultBlockState()
            .setValue(NORTH, attachNorth)
            .setValue(SOUTH, attachSouth)
            .setValue(WEST, attachWest)
            .setValue(EAST, attachEast)
            .setValue(WATERLOGGED, replacedFluidState.is(Fluids.WATER));
        // MODIFIED for porting: was VFP block/interaction MixinIronBarsBlock#changePlacementState (@Inject RETURN,
        // cancellable) - <= 1.8 rendered a pane that attached to nothing as the full post cross, so such a pane is
        // placed with all four sides connected.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8) && connections == 0) {
            return placementState.setValue(NORTH, true).setValue(SOUTH, true).setValue(WEST, true).setValue(EAST, true);
        }

        return placementState;
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
                this.attachsTo(neighbourState, neighbourState.isFaceSturdy(level, neighbourPos, directionToNeighbour.getOpposite()))
            )
            : super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    @Override
    protected VoxelShape getVisualShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        // MODIFIED for porting: was VFP block/shape MixinIronBarsBlock#useCollisionVisualShape (@Inject HEAD, cancellable)
        // <= 1.15.2 panes had a real visual shape, so suffocation and the camera use the collision geometry instead of
        // the empty shape 1.16+ reports.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_15_2)) {
            return this.getCollisionShape(state, level, pos, context);
        }

        return Shapes.empty();
    }

    @Override
    protected boolean skipRendering(final BlockState state, final BlockState neighborState, final Direction direction) {
        if (neighborState.is(this)
            || neighborState.is(BlockTags.BARS) && state.is(BlockTags.BARS) && neighborState.hasProperty(PROPERTY_BY_DIRECTION.get(direction.getOpposite()))) {
            if (!direction.getAxis().isHorizontal()) {
                return true;
            }

            if (state.getValue(PROPERTY_BY_DIRECTION.get(direction)) && neighborState.getValue(PROPERTY_BY_DIRECTION.get(direction.getOpposite()))) {
                return true;
            }
        }

        return super.skipRendering(state, neighborState, direction);
    }

    public final boolean attachsTo(final BlockState state, final boolean faceSolid) {
        return !isExceptionForConnection(state) && faceSolid || state.getBlock() instanceof IronBarsBlock || state.is(BlockTags.WALLS);
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, WEST, SOUTH, WATERLOGGED);
    }
}