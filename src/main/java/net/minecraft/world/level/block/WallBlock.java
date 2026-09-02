package net.minecraft.world.level.block;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import com.viaversion.viafabricplus.features.block.interaction.Block1_14;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class WallBlock extends Block implements SimpleWaterloggedBlock {
    public static final MapCodec<WallBlock> CODEC = simpleCodec(WallBlock::new);
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final EnumProperty<WallSide> EAST = BlockStateProperties.EAST_WALL;
    public static final EnumProperty<WallSide> NORTH = BlockStateProperties.NORTH_WALL;
    public static final EnumProperty<WallSide> SOUTH = BlockStateProperties.SOUTH_WALL;
    public static final EnumProperty<WallSide> WEST = BlockStateProperties.WEST_WALL;
    public static final Map<Direction, EnumProperty<WallSide>> PROPERTY_BY_DIRECTION = ImmutableMap.copyOf(
        Maps.newEnumMap(Map.of(Direction.NORTH, NORTH, Direction.EAST, EAST, Direction.SOUTH, SOUTH, Direction.WEST, WEST))
    );
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private final Function<BlockState, VoxelShape> shapes;
    private final Function<BlockState, VoxelShape> collisionShapes;
    private static final VoxelShape TEST_SHAPE_POST = Block.column(2.0, 0.0, 16.0);
    private static final Map<Direction, VoxelShape> TEST_SHAPES_WALL = Shapes.rotateHorizontal(Block.boxZ(2.0, 16.0, 0.0, 9.0));
    // MODIFIED for porting: was VFP block/shape MixinWallBlock @Unique state
    // (viaFabricPlus$collision_shape_r1_12_2, viaFabricPlus$outline_shape_r1_12_2, viaFabricPlus$shapeIndexCache_r1_12_2)
    // 1.12.2 and older walls are a fixed 16-entry table indexed by the 4-bit N/E/S/W side mask, not a per-state
    // function, and the mask of each state is memoised.
    private final VoxelShape[] vfpCollisionShapeR1_12_2;
    private final VoxelShape[] vfpOutlineShapeR1_12_2;
    private final Object2IntMap<BlockState> vfpShapeIndexCacheR1_12_2 = new Object2IntOpenHashMap<>();

    @Override
    public MapCodec<WallBlock> codec() {
        return CODEC;
    }

    public WallBlock(final BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(
            this.stateDefinition
                .any()
                .setValue(UP, true)
                .setValue(NORTH, WallSide.NONE)
                .setValue(EAST, WallSide.NONE)
                .setValue(SOUTH, WallSide.NONE)
                .setValue(WEST, WallSide.NONE)
                .setValue(WATERLOGGED, false)
        );
        this.shapes = this.makeShapes(16.0F, 14.0F);
        this.collisionShapes = this.makeShapes(24.0F, 24.0F);
        // MODIFIED for porting: was VFP block/shape MixinWallBlock#initShapes1_12_2 (@Inject <init> RETURN)
        this.vfpCollisionShapeR1_12_2 = this.vfpCreateShapes1_12_2(24.0F, 24.0F);
        this.vfpOutlineShapeR1_12_2 = this.vfpCreateShapes1_12_2(16.0F, 14.0F);
    }

    private Function<BlockState, VoxelShape> makeShapes(final float postHeight, final float wallTop) {
        VoxelShape post = Block.column(8.0, 0.0, postHeight);
        int width = 6;
        Map<Direction, VoxelShape> low = Shapes.rotateHorizontal(Block.boxZ(6.0, 0.0, wallTop, 0.0, 11.0));
        Map<Direction, VoxelShape> tall = Shapes.rotateHorizontal(Block.boxZ(6.0, 0.0, postHeight, 0.0, 11.0));
        return this.getShapeForEachState(state -> {
            VoxelShape shape = state.getValue(UP) ? post : Shapes.empty();

            for (Entry<Direction, EnumProperty<WallSide>> entry : PROPERTY_BY_DIRECTION.entrySet()) {
                shape = Shapes.or(shape, switch ((WallSide)state.getValue(entry.getValue())) {
                    case NONE -> Shapes.empty();
                    case LOW -> (VoxelShape)low.get(entry.getKey());
                    case TALL -> (VoxelShape)tall.get(entry.getKey());
                });
            }

            return shape;
        }, WATERLOGGED);
    }

    // MODIFIED for porting: was VFP block/shape MixinWallBlock#viaFabricPlus$createShapes1_12_2 (@Unique helper)
    // The 1.12.2 wall lattice: a 4..12 post of height1 OR-ed with one of 16 per-side-mask boxes of height2.
    private VoxelShape[] vfpCreateShapes1_12_2(final float height1, final float height2) {
        final float f = 4.0F;
        final float g = 12.0F;
        final float h = 5.0F;
        final float i = 11.0F;

        final VoxelShape baseShape = Block.box(f, 0.0, f, g, height1, g);
        final VoxelShape northShape = Block.box(h, 0.0, 0.0, i, height2, i);
        final VoxelShape southShape = Block.box(h, 0.0, h, i, height2, 16.0);
        final VoxelShape westShape = Block.box(0.0, 0.0, h, i, height2, i);
        final VoxelShape eastShape = Block.box(h, 0.0, h, 16.0, height2, i);
        final VoxelShape[] voxelShapes = new VoxelShape[]{
            Shapes.empty(),
            Block.box(f, 0.0, h, g, height1, 16.0),
            Block.box(0.0, 0.0, f, i, height1, g),
            Block.box(f - 4, 0.0, h - 1, g, height1, 16.0),
            Block.box(f, 0.0, 0.0, g, height1, i),
            Shapes.or(southShape, northShape),
            Block.box(f - 4, 0.0, 0.0, g, height1, i + 1),
            Block.box(f - 4, 0.0, h - 5, g, height1, 16.0),
            Block.box(h, 0.0, f, 16.0, height1, g),
            Block.box(h - 1, 0.0, f, 16.0, height1, g + 4),
            Shapes.or(westShape, eastShape),
            Block.box(h - 5, 0.0, f, 16.0, height1, g + 4),
            Block.box(f, 0.0, 0.0, g + 4, height1, i + 1),
            Block.box(f, 0.0, 0.0, g + 4, height1, i + 5),
            Block.box(h - 5, 0.0, f - 4, 16.0, height1, g),
            Block.box(0.0, 0.0, 0.0, 16.0, height1, 16.0)
        };

        for (int j = 0; j < 16; ++j) {
            voxelShapes[j] = Shapes.or(baseShape, voxelShapes[j]);
        }

        return voxelShapes;
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        // MODIFIED for porting: was VFP block/shape MixinWallBlock#changeOutlineShape (@Inject HEAD, cancellable)
        // <= 1.12.2 walls with the post up are outlined by the fixed 16/14-high table instead of the per-state shape.
        if (state.getValue(UP) && ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            return this.vfpOutlineShapeR1_12_2[this.vfpGetShapeIndex(state)];
        }

        return this.shapes.apply(state);
    }

    @Override
    protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        // MODIFIED for porting: was VFP block/shape MixinWallBlock#changeCollisionShape (@Inject HEAD, cancellable)
        // Same table as above, built 24 high, so walking into a <= 1.12.2 wall collides with the legacy boxes.
        if (state.getValue(UP) && ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            return this.vfpCollisionShapeR1_12_2[this.vfpGetShapeIndex(state)];
        }

        return this.collisionShapes.apply(state);
    }

    // MODIFIED for porting: was VFP block/shape MixinWallBlock#getOcclusionShape (@Override, added method)
    // On <= 1.12.2 this deliberately hands back the vanilla outline function, so light occlusion keeps vanilla
    // geometry instead of inheriting the legacy table getShape hands out above.
    @Override
    protected VoxelShape getOcclusionShape(final BlockState state) {
        if (state.getValue(UP) && ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            return this.shapes.apply(state);
        } else {
            return super.getOcclusionShape(state);
        }
    }

    // MODIFIED for porting: was VFP block/shape MixinWallBlock#viaFabricPlus$getShapeIndex and
    // viaFabricPlus$getDirectionMask (@Unique helpers) - the memoised 4-bit side mask both legacy tables are indexed by.
    private int vfpGetShapeIndex(final BlockState state) {
        return this.vfpShapeIndexCacheR1_12_2.computeIntIfAbsent(state, statex -> {
            int i = 0;
            if (!WallSide.NONE.equals(statex.getValue(NORTH))) {
                i |= vfpGetDirectionMask(Direction.NORTH);
            }

            if (!WallSide.NONE.equals(statex.getValue(EAST))) {
                i |= vfpGetDirectionMask(Direction.EAST);
            }

            if (!WallSide.NONE.equals(statex.getValue(SOUTH))) {
                i |= vfpGetDirectionMask(Direction.SOUTH);
            }

            if (!WallSide.NONE.equals(statex.getValue(WEST))) {
                i |= vfpGetDirectionMask(Direction.WEST);
            }

            return i;
        });
    }

    private static int vfpGetDirectionMask(final Direction dir) {
        return 1 << dir.get2DDataValue();
    }

    @Override
    protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
        return false;
    }

    private boolean connectsTo(final BlockState state, final boolean faceSolid, final Direction direction) {
        Block block = state.getBlock();
        boolean connectedFenceGate = block instanceof FenceGateBlock && FenceGateBlock.connectsToDirection(state, direction);
        // MODIFIED for porting: was VFP block/shape MixinWallBlock#shouldConnectTo1_14 (@Inject RETURN, cancellable)
        // <= 1.14 a wall only ever attached to the blocks the piston attachment rules treat as exceptions, so every
        // other neighbour stays disconnected no matter what the vanilla rule says.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_14) && !Block1_14.isExceptBlockForAttachWithPiston(block)) {
            return false;
        }

        return state.is(BlockTags.WALLS) || !isExceptionForConnection(state) && faceSolid || block instanceof IronBarsBlock || connectedFenceGate;
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        LevelReader level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        FluidState replacedFluidState = context.getLevel().getFluidState(context.getClickedPos());
        BlockPos northPos = pos.north();
        BlockPos eastPos = pos.east();
        BlockPos southPos = pos.south();
        BlockPos westPos = pos.west();
        BlockPos topPos = pos.above();
        BlockState northState = level.getBlockState(northPos);
        BlockState eastState = level.getBlockState(eastPos);
        BlockState southState = level.getBlockState(southPos);
        BlockState westState = level.getBlockState(westPos);
        BlockState topState = level.getBlockState(topPos);
        boolean north = this.connectsTo(northState, northState.isFaceSturdy(level, northPos, Direction.SOUTH), Direction.SOUTH);
        boolean east = this.connectsTo(eastState, eastState.isFaceSturdy(level, eastPos, Direction.WEST), Direction.WEST);
        boolean south = this.connectsTo(southState, southState.isFaceSturdy(level, southPos, Direction.NORTH), Direction.NORTH);
        boolean west = this.connectsTo(westState, westState.isFaceSturdy(level, westPos, Direction.EAST), Direction.EAST);
        BlockState state = this.defaultBlockState().setValue(WATERLOGGED, replacedFluidState.is(Fluids.WATER));
        final BlockState placementState = this.updateShape(level, state, topPos, topState, north, east, south, west);
        // MODIFIED for porting: was VFP block/shape MixinWallBlock#modifyPlacementState (@Inject RETURN, cancellable)
        // <= 1.15.2 had no TALL wall sides at all, see vfpOldWallPlacementLogic below.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_15_2)) {
            return vfpOldWallPlacementLogic(placementState);
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

        final BlockState updatedState;
        if (directionToNeighbour == Direction.DOWN) {
            updatedState = super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
        } else {
            updatedState = directionToNeighbour == Direction.UP
                ? this.topUpdate(level, state, neighbourPos, neighbourState)
                : this.sideUpdate(level, pos, state, neighbourPos, neighbourState, directionToNeighbour);
        }

        // MODIFIED for porting: was VFP block/shape MixinWallBlock#modifyBlockState (@Inject RETURN, cancellable)
        // The vanilla result of every exit path goes through the same <= 1.15.2 demotion as the placement state, so
        // neighbour updates cannot reintroduce a TALL side. The branches above are only bound to a local for that.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_15_2)) {
            return vfpOldWallPlacementLogic(updatedState);
        }

        return updatedState;
    }

    // MODIFIED for porting: was VFP block/shape MixinWallBlock#viaFabricPlus$oldWallPlacementLogic (@Unique helper)
    // <= 1.15.2 walls had no TALL side: every TALL side is demoted to LOW, and if any side was TALL the post is
    // forced up, which is how the pre-1.16 wall shapes were reconstructed.
    private static BlockState vfpOldWallPlacementLogic(BlockState state) {
        boolean addUp = false;
        if (state.getValue(NORTH) == WallSide.TALL) {
            state = state.setValue(NORTH, WallSide.LOW);
            addUp = true;
        }

        if (state.getValue(EAST) == WallSide.TALL) {
            state = state.setValue(EAST, WallSide.LOW);
            addUp = true;
        }

        if (state.getValue(SOUTH) == WallSide.TALL) {
            state = state.setValue(SOUTH, WallSide.LOW);
            addUp = true;
        }

        if (state.getValue(WEST) == WallSide.TALL) {
            state = state.setValue(WEST, WallSide.LOW);
            addUp = true;
        }

        if (addUp) {
            state = state.setValue(UP, true);
        }

        return state;
    }

    private static boolean isConnected(final BlockState state, final Property<WallSide> northWall) {
        return state.getValue(northWall) != WallSide.NONE;
    }

    private static boolean isCovered(final VoxelShape aboveShape, final VoxelShape testShape) {
        return !Shapes.joinIsNotEmpty(testShape, aboveShape, BooleanOp.ONLY_FIRST);
    }

    private BlockState topUpdate(final LevelReader level, final BlockState state, final BlockPos topPos, final BlockState topNeighbour) {
        boolean north = isConnected(state, NORTH);
        boolean east = isConnected(state, EAST);
        boolean south = isConnected(state, SOUTH);
        boolean west = isConnected(state, WEST);
        return this.updateShape(level, state, topPos, topNeighbour, north, east, south, west);
    }

    private BlockState sideUpdate(
        final LevelReader level, final BlockPos pos, final BlockState state, final BlockPos neighbourPos, final BlockState neighbour, final Direction direction
    ) {
        Direction opposite = direction.getOpposite();
        boolean isNorthConnected = direction == Direction.NORTH
            ? this.connectsTo(neighbour, neighbour.isFaceSturdy(level, neighbourPos, opposite), opposite)
            : isConnected(state, NORTH);
        boolean isEastConnected = direction == Direction.EAST
            ? this.connectsTo(neighbour, neighbour.isFaceSturdy(level, neighbourPos, opposite), opposite)
            : isConnected(state, EAST);
        boolean isSouthConnected = direction == Direction.SOUTH
            ? this.connectsTo(neighbour, neighbour.isFaceSturdy(level, neighbourPos, opposite), opposite)
            : isConnected(state, SOUTH);
        boolean isWestConnected = direction == Direction.WEST
            ? this.connectsTo(neighbour, neighbour.isFaceSturdy(level, neighbourPos, opposite), opposite)
            : isConnected(state, WEST);
        BlockPos above = pos.above();
        BlockState aboveState = level.getBlockState(above);
        return this.updateShape(level, state, above, aboveState, isNorthConnected, isEastConnected, isSouthConnected, isWestConnected);
    }

    private BlockState updateShape(
        final LevelReader level,
        final BlockState state,
        final BlockPos topPos,
        final BlockState topNeighbour,
        final boolean north,
        final boolean east,
        final boolean south,
        final boolean west
    ) {
        VoxelShape aboveShape = topNeighbour.getCollisionShape(level, topPos).getFaceShape(Direction.DOWN);
        BlockState sidesUpdatedState = this.updateSides(state, north, east, south, west, aboveShape);
        return sidesUpdatedState.setValue(UP, this.shouldRaisePost(sidesUpdatedState, topNeighbour, aboveShape));
    }

    private boolean shouldRaisePost(final BlockState state, final BlockState topNeighbour, final VoxelShape aboveShape) {
        boolean topNeighbourHasPost = topNeighbour.getBlock() instanceof WallBlock && topNeighbour.getValue(UP);
        if (topNeighbourHasPost) {
            return true;
        }

        WallSide northWall = state.getValue(NORTH);
        WallSide southWall = state.getValue(SOUTH);
        WallSide eastWall = state.getValue(EAST);
        WallSide westWall = state.getValue(WEST);
        boolean southNone = southWall == WallSide.NONE;
        boolean westNone = westWall == WallSide.NONE;
        boolean eastNone = eastWall == WallSide.NONE;
        boolean northNone = northWall == WallSide.NONE;
        boolean hasCorner = northNone && southNone && westNone && eastNone || northNone != southNone || westNone != eastNone;
        if (hasCorner) {
            return true;
        }

        boolean hasHighWall = northWall == WallSide.TALL && southWall == WallSide.TALL || eastWall == WallSide.TALL && westWall == WallSide.TALL;
        return hasHighWall ? false : topNeighbour.is(BlockTags.WALL_POST_OVERRIDE) || isCovered(aboveShape, TEST_SHAPE_POST);
    }

    private BlockState updateSides(
        final BlockState state,
        final boolean northConnection,
        final boolean eastConnection,
        final boolean southConnection,
        final boolean westConnection,
        final VoxelShape aboveShape
    ) {
        return state.setValue(NORTH, this.makeWallState(northConnection, aboveShape, TEST_SHAPES_WALL.get(Direction.NORTH)))
            .setValue(EAST, this.makeWallState(eastConnection, aboveShape, TEST_SHAPES_WALL.get(Direction.EAST)))
            .setValue(SOUTH, this.makeWallState(southConnection, aboveShape, TEST_SHAPES_WALL.get(Direction.SOUTH)))
            .setValue(WEST, this.makeWallState(westConnection, aboveShape, TEST_SHAPES_WALL.get(Direction.WEST)));
    }

    private WallSide makeWallState(final boolean connectsToSide, final VoxelShape aboveShape, final VoxelShape testShape) {
        if (connectsToSide) {
            return isCovered(aboveShape, testShape) ? WallSide.TALL : WallSide.LOW;
        } else {
            return WallSide.NONE;
        }
    }

    @Override
    protected FluidState getFluidState(final BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected boolean propagatesSkylightDown(final BlockState state) {
        return !state.getValue(WATERLOGGED);
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(UP, NORTH, EAST, WEST, SOUTH, WATERLOGGED);
    }

    @Override
    protected BlockState rotate(final BlockState state, final Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_180 -> (BlockState)state.setValue(NORTH, state.getValue(SOUTH))
                .setValue(EAST, state.getValue(WEST))
                .setValue(SOUTH, state.getValue(NORTH))
                .setValue(WEST, state.getValue(EAST));
            case COUNTERCLOCKWISE_90 -> (BlockState)state.setValue(NORTH, state.getValue(EAST))
                .setValue(EAST, state.getValue(SOUTH))
                .setValue(SOUTH, state.getValue(WEST))
                .setValue(WEST, state.getValue(NORTH));
            case CLOCKWISE_90 -> (BlockState)state.setValue(NORTH, state.getValue(WEST))
                .setValue(EAST, state.getValue(NORTH))
                .setValue(SOUTH, state.getValue(EAST))
                .setValue(WEST, state.getValue(SOUTH));
            default -> state;
        };
    }

    @Override
    protected BlockState mirror(final BlockState state, final Mirror mirror) {
        switch (mirror) {
            case LEFT_RIGHT:
                return state.setValue(NORTH, state.getValue(SOUTH)).setValue(SOUTH, state.getValue(NORTH));
            case FRONT_BACK:
                return state.setValue(EAST, state.getValue(WEST)).setValue(WEST, state.getValue(EAST));
            default:
                return super.mirror(state, mirror);
        }
    }
}