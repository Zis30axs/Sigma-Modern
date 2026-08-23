package net.minecraft.world.level.material;

import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.objects.Object2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2BooleanMap;
import it.unimi.dsi.fastutil.shorts.Short2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class FlowingFluid extends Fluid {
    public static final BooleanProperty FALLING = BlockStateProperties.FALLING;
    public static final IntegerProperty LEVEL = BlockStateProperties.LEVEL_FLOWING;
    private static final int CACHE_SIZE = 200;
    private static final ThreadLocal<Object2ByteLinkedOpenHashMap<FlowingFluid.BlockStatePairKey>> OCCLUSION_CACHE = ThreadLocal.withInitial(() -> {
        Object2ByteLinkedOpenHashMap<FlowingFluid.BlockStatePairKey> map = new Object2ByteLinkedOpenHashMap<FlowingFluid.BlockStatePairKey>(200) {
            @Override
            protected void rehash(final int newN) {
            }
        };
        map.defaultReturnValue((byte)127);
        return map;
    });
    private final Map<FluidState, VoxelShape> shapes = Maps.newIdentityHashMap();

    @Override
    protected void createFluidStateDefinition(final StateDefinition.Builder<Fluid, FluidState> builder) {
        builder.add(FALLING);
    }

    @Override
    public Vec3 getFlow(final BlockGetter level, final BlockPos pos, final FluidState fluidState) {
        double flowX = 0.0;
        double flowZ = 0.0;
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            blockPos.setWithOffset(pos, direction);
            FluidState neighbourFluid = level.getFluidState(blockPos);
            if (this.affectsFlow(neighbourFluid)) {
                float neighborHeight = neighbourFluid.getOwnHeight();
                float distance = 0.0F;
                if (neighborHeight == 0.0F) {
                    if (!level.getBlockState(blockPos).blocksMotion()) {
                        BlockPos neighborPos = blockPos.below();
                        FluidState belowNeighborState = level.getFluidState(neighborPos);
                        if (this.affectsFlow(belowNeighborState)) {
                            neighborHeight = belowNeighborState.getOwnHeight();
                            if (neighborHeight > 0.0F) {
                                distance = fluidState.getOwnHeight() - (neighborHeight - 0.8888889F);
                            }
                        }
                    }
                } else if (neighborHeight > 0.0F) {
                    distance = fluidState.getOwnHeight() - neighborHeight;
                }

                if (distance != 0.0F) {
                    flowX += direction.getStepX() * distance;
                    flowZ += direction.getStepZ() * distance;
                }
            }
        }

        Vec3 flow = new Vec3(flowX, 0.0, flowZ);
        if (fluidState.getValue(FALLING)) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                blockPos.setWithOffset(pos, direction);
                if (this.isSolidFace(level, blockPos, direction) || this.isSolidFace(level, blockPos.above(), direction)) {
                    flow = flow.normalize().add(0.0, -6.0, 0.0);
                    break;
                }
            }
        }

        return flow.normalize();
    }

    private boolean affectsFlow(final FluidState neighbourFluid) {
        return neighbourFluid.isEmpty() || neighbourFluid.getType().isSame(this);
    }

    protected boolean isSolidFace(final BlockGetter level, final BlockPos pos, final Direction direction) {
        BlockState state = level.getBlockState(pos);
        FluidState fluidState = level.getFluidState(pos);
        if (fluidState.getType().isSame(this)) {
            return false;
        } else if (direction == Direction.UP) {
            return true;
        } else {
            return state.getBlock() instanceof IceBlock ? false : state.isFaceSturdy(level, pos, direction);
        }
    }

    protected void spread(final ServerLevel level, final BlockPos pos, final BlockState state, final FluidState fluidState) {
        if (!fluidState.isEmpty()) {
            BlockPos belowPos = pos.below();
            BlockState belowState = level.getBlockState(belowPos);
            FluidState belowFluid = belowState.getFluidState();
            if (this.canMaybePassThrough(level, pos, state, Direction.DOWN, belowPos, belowState, belowFluid)) {
                FluidState newBelowFluid = this.getNewLiquid(level, belowPos, belowState);
                Fluid newBelowFluidType = newBelowFluid.getType();
                if (belowFluid.canBeReplacedWith(level, belowPos, newBelowFluidType, Direction.DOWN)
                    && canHoldSpecificFluid(level, belowPos, belowState, newBelowFluidType)) {
                    this.spreadTo(level, belowPos, belowState, Direction.DOWN, newBelowFluid);
                    if (this.sourceNeighborCount(level, pos) >= 3) {
                        this.spreadToSides(level, pos, fluidState, state);
                    }

                    return;
                }
            }

            if (fluidState.isSource() || !this.isWaterHole(level, pos, state, belowPos, belowState)) {
                this.spreadToSides(level, pos, fluidState, state);
            }
        }
    }

    private void spreadToSides(final ServerLevel level, final BlockPos pos, final FluidState fluidState, final BlockState state) {
        int neighbor = fluidState.getAmount() - this.getDropOff(level);
        if (fluidState.getValue(FALLING)) {
            neighbor = 7;
        }

        if (neighbor > 0) {
            Map<Direction, FluidState> spreads = this.getSpread(level, pos, state);

            for (Entry<Direction, FluidState> entry : spreads.entrySet()) {
                Direction spread = entry.getKey();
                FluidState newNeighborFluid = entry.getValue();
                BlockPos neighborPos = pos.relative(spread);
                this.spreadTo(level, neighborPos, level.getBlockState(neighborPos), spread, newNeighborFluid);
            }
        }
    }

    protected FluidState getNewLiquid(final ServerLevel level, final BlockPos pos, final BlockState state) {
        int highestNeighbor = 0;
        int neighbourSources = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos relativePos = mutablePos.setWithOffset(pos, direction);
            BlockState blockState = level.getBlockState(relativePos);
            FluidState fluidState = blockState.getFluidState();
            if (fluidState.getType().isSame(this) && canPassThroughWall(direction, level, pos, state, relativePos, blockState)) {
                if (fluidState.isSource()) {
                    neighbourSources++;
                }

                highestNeighbor = Math.max(highestNeighbor, fluidState.getAmount());
            }
        }

        if (neighbourSources >= 2 && this.canConvertToSource(level)) {
            BlockState belowState = level.getBlockState(mutablePos.setWithOffset(pos, Direction.DOWN));
            FluidState belowFluid = belowState.getFluidState();
            if (belowState.isSolid() || this.isSourceBlockOfThisType(belowFluid)) {
                return this.getSource(false);
            }
        }

        BlockPos abovePos = mutablePos.setWithOffset(pos, Direction.UP);
        BlockState aboveState = level.getBlockState(abovePos);
        FluidState aboveFluid = aboveState.getFluidState();
        if (!aboveFluid.isEmpty() && aboveFluid.getType().isSame(this) && canPassThroughWall(Direction.UP, level, pos, state, abovePos, aboveState)) {
            return this.getFlowing(8, true);
        }

        int amount = highestNeighbor - this.getDropOff(level);
        return amount <= 0 ? Fluids.EMPTY.defaultFluidState() : this.getFlowing(amount, false);
    }

    private static boolean canPassThroughWall(
        final Direction direction,
        final BlockGetter level,
        final BlockPos sourcePos,
        final BlockState sourceState,
        final BlockPos targetPos,
        final BlockState targetState
    ) {
        if (!SharedConstants.DEBUG_DISABLE_LIQUID_SPREADING && (!SharedConstants.DEBUG_ONLY_GENERATE_HALF_THE_WORLD || targetPos.getZ() >= 0)) {
            VoxelShape targetShape = targetState.getCollisionShape(level, targetPos);
            if (targetShape == Shapes.block()) {
                return false;
            }

            VoxelShape sourceShape = sourceState.getCollisionShape(level, sourcePos);
            if (sourceShape == Shapes.block()) {
                return false;
            }

            if (sourceShape == Shapes.empty() && targetShape == Shapes.empty()) {
                return true;
            }

            Object2ByteLinkedOpenHashMap<FlowingFluid.BlockStatePairKey> cache;
            if (!sourceState.getBlock().hasDynamicShape() && !targetState.getBlock().hasDynamicShape()) {
                cache = OCCLUSION_CACHE.get();
            } else {
                cache = null;
            }

            FlowingFluid.BlockStatePairKey key;
            if (cache != null) {
                key = new FlowingFluid.BlockStatePairKey(sourceState, targetState, direction);
                byte cached = cache.getAndMoveToFirst(key);
                if (cached != 127) {
                    return cached != 0;
                }
            } else {
                key = null;
            }

            boolean result = !Shapes.mergedFaceOccludes(sourceShape, targetShape, direction);
            if (cache != null) {
                if (cache.size() == 200) {
                    cache.removeLastByte();
                }

                cache.putAndMoveToFirst(key, (byte)(result ? 1 : 0));
            }

            return result;
        } else {
            return false;
        }
    }

    public abstract Fluid getFlowing();

    public FluidState getFlowing(final int amount, final boolean falling) {
        return this.getFlowing().defaultFluidState().setValue(LEVEL, amount).setValue(FALLING, falling);
    }

    public abstract Fluid getSource();

    public FluidState getSource(final boolean falling) {
        return this.getSource().defaultFluidState().setValue(FALLING, falling);
    }

    protected abstract boolean canConvertToSource(ServerLevel level);

    protected void spreadTo(final LevelAccessor level, final BlockPos pos, final BlockState state, final Direction direction, final FluidState target) {
        if (state.getBlock() instanceof LiquidBlockContainer container) {
            container.placeLiquid(level, pos, state, target);
        } else {
            if (!state.isAir()) {
                this.beforeDestroyingBlock(level, pos, state);
            }

            level.setBlock(pos, target.createLegacyBlock(), 3);
        }
    }

    protected abstract void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state);

    protected int getSlopeDistance(
        final LevelReader level, final BlockPos pos, final int pass, final Direction from, final BlockState state, final FlowingFluid.SpreadContext context
    ) {
        int lowest = 1000;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (direction != from) {
                BlockPos testPos = pos.relative(direction);
                BlockState testState = context.getBlockState(testPos);
                FluidState testFluidState = testState.getFluidState();
                if (this.canPassThrough(level, this.getFlowing(), pos, state, direction, testPos, testState, testFluidState)) {
                    if (context.isHole(testPos)) {
                        return pass;
                    }

                    if (pass < this.getSlopeFindDistance(level)) {
                        int v = this.getSlopeDistance(level, testPos, pass + 1, direction.getOpposite(), testState, context);
                        if (v < lowest) {
                            lowest = v;
                        }
                    }
                }
            }
        }

        return lowest;
    }

    private boolean isWaterHole(
        final BlockGetter level, final BlockPos topPos, final BlockState topState, final BlockPos bottomPos, final BlockState bottomState
    ) {
        // MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#isWaterHole (@Overwrite) - rearranged to have
        // the cheaper checks first
        return (bottomState.getFluidState().getType().isSame(this) || canHoldFluid(level, bottomPos, bottomState, this.getFlowing()))
            && canPassThroughWall(Direction.DOWN, level, topPos, topState, bottomPos, bottomState);
    }

    private boolean canPassThrough(
        final BlockGetter level,
        final Fluid fluid,
        final BlockPos sourcePos,
        final BlockState sourceState,
        final Direction direction,
        final BlockPos testPos,
        final BlockState testState,
        final FluidState testFluidState
    ) {
        // MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#canPassThrough (@Overwrite) - rearranged to
        // have the cheaper checks first
        return canHoldSpecificFluid(level, testPos, testState, fluid)
            && this.canMaybePassThrough(level, sourcePos, sourceState, direction, testPos, testState, testFluidState);
    }

    private boolean canMaybePassThrough(
        final BlockGetter level,
        final BlockPos sourcePos,
        final BlockState sourceState,
        final Direction direction,
        final BlockPos testPos,
        final BlockState testState,
        final FluidState testFluidState
    ) {
        return !this.isSourceBlockOfThisType(testFluidState)
            && canHoldAnyFluid(testState)
            && canPassThroughWall(direction, level, sourcePos, sourceState, testPos, testState);
    }

    private boolean isSourceBlockOfThisType(final FluidState state) {
        return state.getType().isSame(this) && state.isSource();
    }

    protected abstract int getSlopeFindDistance(LevelReader level);

    private int sourceNeighborCount(final LevelReader level, final BlockPos pos) {
        int count = 0;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos testPos = pos.relative(direction);
            FluidState testFluidState = level.getFluidState(testPos);
            if (this.isSourceBlockOfThisType(testFluidState)) {
                count++;
            }
        }

        return count;
    }

    protected Map<Direction, FluidState> getSpread(final ServerLevel level, final BlockPos pos, final BlockState state) {
        // MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#getSpread (HEAD, cancellable). It returns null
        // only when the slope find distance is too large for its byte-indexed caches, in which case the vanilla code below
        // runs unchanged.
        Map<Direction, FluidState> lithium$spread = this.lithium$getSpread(level, pos, state);
        if (lithium$spread != null) {
            return lithium$spread;
        }

        int lowest = 1000;
        Map<Direction, FluidState> result = Maps.newEnumMap(Direction.class);
        FlowingFluid.SpreadContext context = null;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos testPos = pos.relative(direction);
            BlockState testState = level.getBlockState(testPos);
            FluidState testFluidState = testState.getFluidState();
            if (this.canMaybePassThrough(level, pos, state, direction, testPos, testState, testFluidState)) {
                FluidState newFluid = this.getNewLiquid(level, testPos, testState);
                if (canHoldSpecificFluid(level, testPos, testState, newFluid.getType())) {
                    if (context == null) {
                        context = new FlowingFluid.SpreadContext(level, pos);
                    }

                    int distance;
                    if (context.isHole(testPos)) {
                        distance = 0;
                    } else {
                        distance = this.getSlopeDistance(level, testPos, 1, direction.getOpposite(), testState, context);
                    }

                    if (distance < lowest) {
                        result.clear();
                    }

                    if (distance <= lowest) {
                        if (testFluidState.canBeReplacedWith(level, testPos, newFluid.getType(), direction)) {
                            result.put(direction, newFluid);
                        }

                        lowest = distance;
                    }
                }
            }
        }

        return result;
    }


    /**
     * MODIFIED for porting: was lithium's block.fluid.flow FlowingFluidMixin#getSpread, by 2No2Name.
     * <p>
     * Check the immediate walls to see whether branching is possible (at most 2 walls). If branching is possible, do the
     * complex flow calculation; otherwise just handle the single possible direction.
     *
     * @return the flow result, or null if the search radius is too large and the caller has to fall back to vanilla
     */
    private @org.jspecify.annotations.Nullable Map<Direction, FluidState> lithium$getSpread(final ServerLevel world, final BlockPos pos, final BlockState state) {
        Map<Direction, FluidState> flowResultByDirection = Maps.newEnumMap(Direction.class);
        int searchRadius = this.getSlopeFindDistance(world) + 1;
        int numIndicesFromRadius = lithium$getNumIndicesFromRadius(searchRadius);
        if (numIndicesFromRadius > 256) {
            // We use bytes to represent the indices, which works with the vanilla search radius of up to 5.
            // Fall back to vanilla code in case the search radius is too large.
            return null;
        }

        BlockState[] blockStateCache = new BlockState[numIndicesFromRadius];
        Direction onlyPossibleFlowDirection = null;
        BlockPos onlyBlockPos = null;
        BlockState onlyBlockState = null;

        for (Direction flowDirection : net.caffeinemc.mods.lithium.common.util.DirectionConstants.HORIZONTAL) {
            BlockPos flowTargetPos = pos.relative(flowDirection);
            byte blockIndex = lithium$indexFromDiamondXZOffset(pos, flowTargetPos, searchRadius);
            BlockState flowTargetBlock = world.getBlockState(flowTargetPos);
            blockStateCache[blockIndex] = flowTargetBlock;
            if (this.lithium$canMaybeFlowIntoBlock(world, flowTargetBlock, flowTargetPos)) {
                if (onlyPossibleFlowDirection == null) {
                    onlyPossibleFlowDirection = flowDirection;
                    onlyBlockPos = flowTargetPos;
                    onlyBlockState = flowTargetBlock;
                } else {
                    this.lithium$calculateComplexFluidFlowDirections(world, pos, state, blockStateCache, flowResultByDirection);
                    return flowResultByDirection;
                }
            }
        }

        if (onlyPossibleFlowDirection != null) {
            FluidState onlyFluidState = onlyBlockState.getFluidState();
            if (this.canMaybePassThrough(world, pos, state, onlyPossibleFlowDirection, onlyBlockPos, onlyBlockState, onlyFluidState)) {
                FluidState targetNewFluidState = this.getNewLiquid(world, onlyBlockPos, onlyBlockState);
                if (canHoldSpecificFluid(world, onlyBlockPos, onlyBlockState, targetNewFluidState.getType())
                    && onlyFluidState.canBeReplacedWith(world, onlyBlockPos, targetNewFluidState.getType(), onlyPossibleFlowDirection)) {
                    flowResultByDirection.put(onlyPossibleFlowDirection, targetNewFluidState);
                }
            }
        }

        return flowResultByDirection;
    }

    // MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#getNumIndicesFromRadius
    private static int lithium$getNumIndicesFromRadius(final int radius) {
        return (radius + 1) * (2 * radius + 1);
    }

    // MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#indexFromDiamondXZOffset
    private static byte lithium$indexFromDiamondXZOffset(final BlockPos originPos, final BlockPos offsetPos, final int radius) {
        int xOffset = offsetPos.getX() - originPos.getX();
        int zOffset = offsetPos.getZ() - originPos.getZ();
        int row = (xOffset + zOffset + radius) / 2; // Range [0, radius]
        int column = xOffset - zOffset + radius; // Range [0, 2*radius]
        int rowLength = 2 * radius + 1;
        return (byte)(row * rowLength + column);
    }

    // MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#getBlock
    private BlockState lithium$getBlock(final Level world, final BlockPos pos, final BlockState[] blockStateCache, final byte byteKey) {
        int key = Byte.toUnsignedInt(byteKey);
        BlockState blockState = blockStateCache[key];
        if (blockState == null) {
            blockState = world.getBlockState(pos);
            blockStateCache[key] = blockState;
        }

        return blockState;
    }

    // MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#removeDirectionsWithoutHoleAccess
    private void lithium$removeDirectionsWithoutHoleAccess(final byte holeAccess, final Map<Direction, FluidState> flowResultByDirection) {
        for (int i = 0; i < net.caffeinemc.mods.lithium.common.util.DirectionConstants.HORIZONTAL.length; i++) {
            if ((holeAccess & 1 << i) == 0) {
                flowResultByDirection.remove(net.caffeinemc.mods.lithium.common.util.DirectionConstants.HORIZONTAL[i]);
            }
        }
    }

    /**
     * MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#canMaybeFlowIntoBlock.
     * Fast check to eliminate some choices for the flow direction.
     */
    private boolean lithium$canMaybeFlowIntoBlock(final Level world, final BlockState blockState, final BlockPos flowTargetPos) {
        return canHoldFluid(world, flowTargetPos, blockState, this.getSource());
    }

    /**
     * MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#calculateComplexFluidFlowDirections.
     * <p>
     * Search like breadth-first-search for paths the fluid can flow. Only move in directions the fluid can move (e.g. block
     * can contain / be replaced by fluid) (vanilla conditions). For each node remember the first move step (direction) of the
     * paths that led to this node. Break when the BFS found all paths of a length up to some length, if any of those paths
     * found a node with a hole below. Then return the union of the stored first move steps of the nodes with a hole below.
     * In total, this finds the directions from the starting location which are the first step towards one of the closest
     * holes, just like vanilla.
     */
    private void lithium$calculateComplexFluidFlowDirections(
        final ServerLevel world,
        final BlockPos startPos,
        final BlockState startState,
        final BlockState[] blockStateCache,
        final Map<Direction, FluidState> flowResultByDirection
    ) {
        // For each relevant position: is there a hole below; what is the shortest path length to the center; which direct
        // neighbors of the center are on a shortest path to this location (4 bits); which direct neighbors of the position
        // are the previous node on the path from the center (4 bits).
        it.unimi.dsi.fastutil.bytes.Byte2ByteOpenHashMap prevPositions = new it.unimi.dsi.fastutil.bytes.Byte2ByteOpenHashMap();
        it.unimi.dsi.fastutil.bytes.Byte2ByteOpenHashMap currentPositions = new it.unimi.dsi.fastutil.bytes.Byte2ByteOpenHashMap();
        it.unimi.dsi.fastutil.bytes.Byte2BooleanOpenHashMap holeCache = new it.unimi.dsi.fastutil.bytes.Byte2BooleanOpenHashMap();
        byte holeAccess = 0;
        int searchRadius = this.getSlopeFindDistance(world) + 1;

        // Like vanilla, the first iteration is separate, because getNewLiquid is called to check whether a renewable fluid
        // source block is created in the flow direction.
        for (int i = 0; i < net.caffeinemc.mods.lithium.common.util.DirectionConstants.HORIZONTAL.length; i++) {
            Direction flowDirection = net.caffeinemc.mods.lithium.common.util.DirectionConstants.HORIZONTAL[i];
            BlockPos flowTargetPos = startPos.relative(flowDirection);
            byte blockIndex = lithium$indexFromDiamondXZOffset(startPos, flowTargetPos, searchRadius);
            BlockState targetBlockState = this.lithium$getBlock(world, flowTargetPos, blockStateCache, blockIndex);
            if (this.canMaybePassThrough(world, startPos, startState, flowDirection, flowTargetPos, targetBlockState, targetBlockState.getFluidState())) {
                FluidState targetNewFluidState = this.getNewLiquid(world, flowTargetPos, targetBlockState);
                if (canHoldSpecificFluid(world, flowTargetPos, targetBlockState, targetNewFluidState.getType())) {
                    // Store the resulting fluid state for each direction, remove later if there is no closest hole access
                    // in this direction. 1.21.2+ speciality: only add the direction if the fluid can replace the other
                    // fluid. If it cannot, it still counts for the hole search though.
                    if (targetBlockState.getFluidState().canBeReplacedWith(world, flowTargetPos, targetNewFluidState.getType(), flowDirection)) {
                        flowResultByDirection.put(flowDirection, targetNewFluidState);
                    }

                    if (this.canPassThrough(
                        world,
                        targetNewFluidState.getType(),
                        startPos,
                        startState,
                        flowDirection,
                        flowTargetPos,
                        targetBlockState,
                        targetBlockState.getFluidState()
                    )) {
                        prevPositions.put(blockIndex, (byte)(0b10001 << i));
                        if (this.lithium$isHoleBelow(world, holeCache, blockIndex, flowTargetPos, targetBlockState)) {
                            holeAccess |= (byte)(1 << i);
                        }
                    }
                }
            }
        }

        // Iterate over the positions and find the shortest path to the center. If a hole is found, stop the iteration.
        for (int i = 0; i < this.getSlopeFindDistance(world) && holeAccess == 0; i++) {
            Fluid targetFluid = this.getFlowing();

            for (it.unimi.dsi.fastutil.objects.ObjectIterator<it.unimi.dsi.fastutil.bytes.Byte2ByteMap.Entry> iterator = prevPositions.byte2ByteEntrySet().fastIterator(); iterator.hasNext();) {
                it.unimi.dsi.fastutil.bytes.Byte2ByteMap.Entry entry = iterator.next();
                byte blockIndex = entry.getByteKey();
                byte currentInfo = entry.getByteValue();
                int rowLength = 2 * searchRadius + 1;
                int row = blockIndex / rowLength;
                int column = blockIndex % rowLength;
                int unevenColumn = column % 2;
                int xOffset = (row * 2 + column + unevenColumn - searchRadius * 2) / 2;
                int zOffset = xOffset - column + searchRadius;
                BlockPos currentPos = startPos.offset(xOffset, 0, zOffset);
                BlockState currentState = blockStateCache[blockIndex];

                for (int j = 0; j < net.caffeinemc.mods.lithium.common.util.DirectionConstants.HORIZONTAL.length; j++) {
                    Direction flowDirection = net.caffeinemc.mods.lithium.common.util.DirectionConstants.HORIZONTAL[j];
                    int oppositeDirection = net.caffeinemc.mods.lithium.common.util.DirectionConstants.HORIZONTAL_OPPOSITE_INDICES[j];
                    if ((currentInfo >> 4 & 1 << oppositeDirection) != (byte)0) {
                        // In this direction is one of the disallowed directions
                        continue;
                    }

                    BlockPos flowTargetPos = currentPos.relative(flowDirection);
                    byte targetPosBlockIndex = lithium$indexFromDiamondXZOffset(startPos, flowTargetPos, searchRadius);
                    if (prevPositions.containsKey(targetPosBlockIndex)) {
                        continue;
                    }

                    byte oldInfo = currentPositions.getOrDefault(targetPosBlockIndex, (byte)0);
                    byte newInfo = oldInfo;
                    newInfo |= (byte)(0b10000 << j); // Disallow search direction
                    newInfo |= (byte)(currentInfo & 0b1111); // Shortest-reachable with the starting directions
                    if ((newInfo & 0b1111) == (oldInfo & 0b1111)) {
                        currentPositions.put(targetPosBlockIndex, newInfo);
                        continue;
                    }

                    BlockState targetBlockState = this.lithium$getBlock(world, flowTargetPos, blockStateCache, targetPosBlockIndex);
                    if (this.canPassThrough(
                        world, targetFluid, currentPos, currentState, flowDirection, flowTargetPos, targetBlockState, targetBlockState.getFluidState()
                    )) {
                        currentPositions.put(targetPosBlockIndex, newInfo);
                        if (this.lithium$isHoleBelow(world, holeCache, targetPosBlockIndex, flowTargetPos, targetBlockState)) {
                            holeAccess |= (byte)(currentInfo & 0b1111);
                        }
                    }
                }
            }

            it.unimi.dsi.fastutil.bytes.Byte2ByteOpenHashMap tmp = prevPositions;
            prevPositions = currentPositions;
            currentPositions = tmp;
            currentPositions.clear();
        }

        if (holeAccess != 0) {
            // Found at least one hole in any iteration, keep the directions which lead to the closest holes.
            this.lithium$removeDirectionsWithoutHoleAccess(holeAccess, flowResultByDirection);
        }
    }

    // MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#isHoleBelow
    private boolean lithium$isHoleBelow(
        final LevelReader world, final it.unimi.dsi.fastutil.bytes.Byte2BooleanOpenHashMap holeCache, final byte key, final BlockPos flowTargetPos, final BlockState targetBlockState
    ) {
        if (holeCache.containsKey(key)) {
            return holeCache.get(key);
        }

        BlockPos downPos = flowTargetPos.below();
        BlockState downBlock = world.getBlockState(downPos);
        boolean holeFound = this.isWaterHole(world, flowTargetPos, targetBlockState, downPos, downBlock);
        holeCache.put(key, holeFound);
        return holeFound;
    }

    private static boolean canHoldAnyFluid(final BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LiquidBlockContainer) {
            return true;
        } else {
            return state.blocksMotion()
                ? false
                : !(block instanceof DoorBlock)
                    // MODIFIED for porting: lithium block.fluid.flow FlowingFluidMixin#isSign - the sign check is
                    // expensive when using the block tag lookup
                    && !(block instanceof net.minecraft.world.level.block.SignBlock)
                    && !state.is(Blocks.LADDER)
                    && !state.is(Blocks.SUGAR_CANE)
                    && !state.is(Blocks.BUBBLE_COLUMN)
                    && !state.is(Blocks.NETHER_PORTAL)
                    && !state.is(Blocks.END_PORTAL)
                    && !state.is(Blocks.END_GATEWAY)
                    && !state.is(Blocks.STRUCTURE_VOID);
        }
    }

    private static boolean canHoldFluid(final BlockGetter level, final BlockPos pos, final BlockState state, final Fluid newFluid) {
        return canHoldAnyFluid(state) && canHoldSpecificFluid(level, pos, state, newFluid);
    }

    private static boolean canHoldSpecificFluid(final BlockGetter level, final BlockPos pos, final BlockState state, final Fluid newFluid) {
        return state.getBlock() instanceof LiquidBlockContainer container ? container.canPlaceLiquid(null, level, pos, state, newFluid) : true;
    }

    protected abstract int getDropOff(LevelReader level);

    protected int getSpreadDelay(final Level level, final BlockPos pos, final FluidState oldFluidState, final FluidState newFluidState) {
        return this.getTickDelay(level);
    }

    @Override
    public void tick(final ServerLevel level, final BlockPos pos, BlockState blockState, FluidState fluidState) {
        if (!fluidState.isSource()) {
            FluidState newFluidState = this.getNewLiquid(level, pos, level.getBlockState(pos));
            int tickDelay = this.getSpreadDelay(level, pos, fluidState, newFluidState);
            if (newFluidState.isEmpty()) {
                fluidState = newFluidState;
                blockState = Blocks.AIR.defaultBlockState();
                level.setBlock(pos, blockState, 3);
            } else if (newFluidState != fluidState) {
                fluidState = newFluidState;
                blockState = fluidState.createLegacyBlock();
                level.setBlock(pos, blockState, 3);
                level.scheduleTick(pos, fluidState.getType(), tickDelay);
            }
        }

        this.spread(level, pos, blockState, fluidState);
    }

    protected static int getLegacyLevel(final FluidState fluidState) {
        return fluidState.isSource() ? 0 : 8 - Math.min(fluidState.getAmount(), 8) + (fluidState.getValue(FALLING) ? 8 : 0);
    }

    private static boolean hasSameAbove(final FluidState fluidState, final BlockGetter level, final BlockPos pos) {
        return fluidState.getType().isSame(level.getFluidState(pos.above()).getType());
    }

    @Override
    public float getHeight(final FluidState fluidState, final BlockGetter level, final BlockPos pos) {
        return hasSameAbove(fluidState, level, pos) ? 1.0F : fluidState.getOwnHeight();
    }

    @Override
    public float getOwnHeight(final FluidState fluidState) {
        return fluidState.getAmount() / 9.0F;
    }

    @Override
    public abstract int getAmount(final FluidState fluidState);

    @Override
    public VoxelShape getShape(final FluidState state, final BlockGetter level, final BlockPos pos) {
        return state.getAmount() == 9 && hasSameAbove(state, level, pos)
            ? Shapes.block()
            : this.shapes.computeIfAbsent(state, fluidState -> Shapes.box(0.0, 0.0, 0.0, 1.0, fluidState.getHeight(level, pos), 1.0));
    }

    // MODIFIED for porting: lithium.accesswidener widened access, and lithium cached_hashcode
    // FlowingFluid$BlockStatePairKeyMixin caches the hash code (this key is looked up for every fluid spread check).
    // Upstream could add the extra field to the record through bytecode; in source a record cannot declare instance
    // fields, so the record is written out as an ordinary final class with the identical accessors, equals and hashCode.
    public static final class BlockStatePairKey {
        private final BlockState first;
        private final BlockState second;
        private final Direction direction;
        private final int hash;

        public BlockStatePairKey(final BlockState first, final BlockState second, final Direction direction) {
            this.first = first;
            this.second = second;
            this.direction = direction;
            // MODIFIED for porting: lithium FlowingFluid$BlockStatePairKeyMixin#generateHash (RETURN of <init>)
            int hash = System.identityHashCode(first);
            hash = 31 * hash + System.identityHashCode(second);
            this.hash = 31 * hash + direction.hashCode();
        }

        public BlockState first() {
            return this.first;
        }

        public BlockState second() {
            return this.second;
        }

        public Direction direction() {
            return this.direction;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof FlowingFluid.BlockStatePairKey that
                && this.first == that.first
                && this.second == that.second
                && this.direction == that.direction;
        }

        @Override
        public int hashCode() {
            // MODIFIED for porting: lithium FlowingFluid$BlockStatePairKeyMixin uses the cached value
            return this.hash;
        }

        @Override
        public String toString() {
            return "BlockStatePairKey[first=" + this.first + ", second=" + this.second + ", direction=" + this.direction + "]";
        }
    }

    protected class SpreadContext {
        private final BlockGetter level;
        private final BlockPos origin;
        private final Short2ObjectMap<BlockState> stateCache = new Short2ObjectOpenHashMap<>();
        private final Short2BooleanMap holeCache = new Short2BooleanOpenHashMap();

        private SpreadContext(final BlockGetter level, final BlockPos origin) {
            this.level = level;
            this.origin = origin;
        }

        public BlockState getBlockState(final BlockPos pos) {
            return this.getBlockState(pos, this.getCacheKey(pos));
        }

        private BlockState getBlockState(final BlockPos pos, final short key) {
            return this.stateCache.computeIfAbsent(key, k -> this.level.getBlockState(pos));
        }

        public boolean isHole(final BlockPos pos) {
            return this.holeCache.computeIfAbsent(this.getCacheKey(pos), key -> {
                BlockState state = this.getBlockState(pos, key);
                BlockPos below = pos.below();
                BlockState belowState = this.level.getBlockState(below);
                return FlowingFluid.this.isWaterHole(this.level, pos, state, below, belowState);
            });
        }

        private short getCacheKey(final BlockPos pos) {
            int relativeX = pos.getX() - this.origin.getX();
            int relativeZ = pos.getZ() - this.origin.getZ();
            return (short)((relativeX + 128 & 0xFF) << 8 | relativeZ + 128 & 0xFF);
        }
    }
}