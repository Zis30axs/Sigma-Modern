package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Util;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// MODIFIED for porting: was VFP block/shape MixinCrossCollisionBlock (ICrossCollisionBlock implementation).
// The legacy fence and iron-bars shape tables are VoxelShape[] indexed by a 4-bit N/E/S/W connection mask, which
// this class computes and memoises for its subclasses.
public abstract class CrossCollisionBlock extends Block
    implements SimpleWaterloggedBlock, com.viaversion.viafabricplus.injection.access.block.shape.ICrossCollisionBlock {
    public static final BooleanProperty NORTH = PipeBlock.NORTH;
    public static final BooleanProperty EAST = PipeBlock.EAST;
    public static final BooleanProperty SOUTH = PipeBlock.SOUTH;
    public static final BooleanProperty WEST = PipeBlock.WEST;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final Map<Direction, BooleanProperty> PROPERTY_BY_DIRECTION = PipeBlock.PROPERTY_BY_DIRECTION
        .entrySet()
        .stream()
        .filter(e -> e.getKey().getAxis().isHorizontal())
        .collect(Util.toMap());
    private final Function<BlockState, VoxelShape> collisionShapes;
    private final Function<BlockState, VoxelShape> shapes;
    // MODIFIED for porting: was VFP block/shape MixinCrossCollisionBlock @Unique
    // viaFabricPlus$SHAPE_INDEX_CACHE - per-block cache of the connection index, ungated because every legacy
    // target version that uses the shape tables needs it.
    private final Object2IntMap<BlockState> vfpShapeIndexCache = new Object2IntOpenHashMap<>();

    protected CrossCollisionBlock(
        final float postWidth,
        final float postHeight,
        final float wallWidth,
        final float wallHeight,
        final float collisionHeight,
        final BlockBehaviour.Properties properties
    ) {
        super(properties);
        this.collisionShapes = this.makeShapes(postWidth, collisionHeight, wallWidth, 0.0F, collisionHeight);
        this.shapes = this.makeShapes(postWidth, postHeight, wallWidth, 0.0F, wallHeight);
    }

    @Override
    protected abstract MapCodec<? extends CrossCollisionBlock> codec();

    protected Function<BlockState, VoxelShape> makeShapes(
        final float postWidth, final float postHeight, final float wallWidth, final float wallBottom, final float wallTop
    ) {
        VoxelShape post = Block.column(postWidth, 0.0, postHeight);
        Map<Direction, VoxelShape> arms = Shapes.rotateHorizontal(Block.boxZ(wallWidth, wallBottom, wallTop, 0.0, 8.0));
        return this.getShapeForEachState(state -> {
            VoxelShape shape = post;

            for (Entry<Direction, BooleanProperty> entry : PROPERTY_BY_DIRECTION.entrySet()) {
                if (state.getValue(entry.getValue())) {
                    shape = Shapes.or(shape, arms.get(entry.getKey()));
                }
            }

            return shape;
        }, WATERLOGGED);
    }

    @Override
    protected boolean propagatesSkylightDown(final BlockState state) {
        return !state.getValue(WATERLOGGED);
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return this.shapes.apply(state);
    }

    @Override
    protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return this.collisionShapes.apply(state);
    }

    @Override
    protected FluidState getFluidState(final BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
        return false;
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

    // MODIFIED for porting: was VFP block/shape MixinCrossCollisionBlock#viaFabricPlus$getShapeIndex
    // (ICrossCollisionBlock implementation) - 0..15 bitmask of the four horizontal connections, in Direction
    // 2D data order, used to index the legacy shape tables in FenceBlock and IronBarsBlock.
    @Override
    public int viaFabricPlus$getShapeIndex(final BlockState state) {
        return this.vfpShapeIndexCache.computeIfAbsent(state, _ -> {
            int index = 0;
            if (state.getValue(CrossCollisionBlock.NORTH)) {
                index |= 1 << Direction.NORTH.get2DDataValue();
            }
            if (state.getValue(CrossCollisionBlock.EAST)) {
                index |= 1 << Direction.EAST.get2DDataValue();
            }
            if (state.getValue(CrossCollisionBlock.SOUTH)) {
                index |= 1 << Direction.SOUTH.get2DDataValue();
            }
            if (state.getValue(CrossCollisionBlock.WEST)) {
                index |= 1 << Direction.WEST.get2DDataValue();
            }

            return index;
        });
    }
}