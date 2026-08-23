package net.minecraft.world.level;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks; // MODIFIED for porting: lithium world.raycast
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public interface BlockGetter extends LevelHeightAccessor {
    @Nullable BlockEntity getBlockEntity(BlockPos pos);

    default <T extends BlockEntity> Optional<T> getBlockEntity(final BlockPos pos, final BlockEntityType<T> type) {
        BlockEntity blockEntity = this.getBlockEntity(pos);
        return blockEntity != null && blockEntity.getType() == type ? Optional.of((T)blockEntity) : Optional.empty();
    }

    BlockState getBlockState(final BlockPos pos);

    FluidState getFluidState(BlockPos pos);

    default int getLightEmission(final BlockPos pos) {
        return this.getBlockState(pos).getLightEmission();
    }

    default Stream<BlockState> getBlockStates(final AABB box) {
        return BlockPos.betweenClosedStream(box).map(this::getBlockState);
    }

    default BlockHitResult isBlockInLine(final ClipBlockStateContext c) {
        return traverseBlocks(
            c.getFrom(),
            c.getTo(),
            c,
            (context, pos) -> {
                BlockState blockState = this.getBlockState(pos);
                Vec3 delta = context.getFrom().subtract(context.getTo());
                return context.isTargetBlock().test(blockState)
                    ? new BlockHitResult(
                        context.getTo(), Direction.getApproximateNearest(delta.x, delta.y, delta.z), BlockPos.containing(context.getTo()), false
                    )
                    : null;
            },
            context -> {
                Vec3 delta = context.getFrom().subtract(context.getTo());
                return BlockHitResult.miss(context.getTo(), Direction.getApproximateNearest(delta.x, delta.y, delta.z), BlockPos.containing(context.getTo()));
            }
        );
    }

    /**
     * MODIFIED for porting: lithium world.raycast BlockGetterMixin. For the two level implementations it can recognise, the
     * per-block callback is replaced by a stateful one that caches the chunk between steps of the ray and skips the fluid
     * lookup entirely when the context does not handle fluids. Custom level implementations keep the vanilla callback, which
     * is what upstream does for compatibility with mods such as Create and Ponder.
     */
    default BlockHitResult clip(final ClipContext c) {
        BiFunction<ClipContext, BlockPos, BlockHitResult> blockHitFactory = this instanceof net.minecraft.server.level.ServerLevel
                || this instanceof Level level && level.isClientSide()
            ? this.lithium$blockHitFactory()
            : (context, pos) -> {
                BlockState blockState = this.getBlockState(pos);
                FluidState fluidState = this.getFluidState(pos);
                Vec3 from = context.getFrom();
                Vec3 to = context.getTo();
                VoxelShape blockShape = context.getBlockShape(blockState, this, pos);
                BlockHitResult blockResult = this.clipWithInteractionOverride(from, to, pos, blockShape, blockState);
                VoxelShape fluidShape = context.getFluidShape(fluidState, this, pos);
                BlockHitResult liquidResult = fluidShape.clip(from, to, pos);
                double blockDistanceSquared = blockResult == null ? Double.MAX_VALUE : context.getFrom().distanceToSqr(blockResult.getLocation());
                double liquidDistanceSquared = liquidResult == null ? Double.MAX_VALUE : context.getFrom().distanceToSqr(liquidResult.getLocation());
                return blockDistanceSquared <= liquidDistanceSquared ? blockResult : liquidResult;
            };
        return traverseBlocks(c.getFrom(), c.getTo(), c, blockHitFactory, context -> {
            Vec3 delta = context.getFrom().subtract(context.getTo());
            return BlockHitResult.miss(context.getTo(), Direction.getApproximateNearest(delta.x, delta.y, delta.z), BlockPos.containing(context.getTo()));
        });
    }

    // MODIFIED for porting: was lithium's world.raycast BlockGetterMixin#lithium$blockHitFactory
    private BiFunction<ClipContext, BlockPos, BlockHitResult> lithium$blockHitFactory() {
        return new BiFunction<>() {
            private int chunkX = Integer.MIN_VALUE;
            private int chunkZ = Integer.MIN_VALUE;
            private net.minecraft.world.level.chunk.ChunkAccess chunk = null;

            @Override
            public BlockHitResult apply(final ClipContext innerContext, final BlockPos pos) {
                // [VanillaCopy] BlockGetter#clip, but with optional fluid handling
                boolean handleFluids = innerContext.getFluidHandling() != ClipContext.Fluid.NONE;
                BlockState blockState = this.getBlock((LevelReader)BlockGetter.this, pos);
                Vec3 start = innerContext.getFrom();
                Vec3 end = innerContext.getTo();
                VoxelShape blockShape = innerContext.getBlockShape(blockState, BlockGetter.this, pos);
                BlockHitResult blockHitResult = BlockGetter.this.clipWithInteractionOverride(start, end, pos, blockShape, blockState);
                double d = blockHitResult == null ? Double.MAX_VALUE : innerContext.getFrom().distanceToSqr(blockHitResult.getLocation());
                double e = Double.MAX_VALUE;
                BlockHitResult fluidHitResult = null;
                if (handleFluids) {
                    FluidState fluidState = blockState.getFluidState();
                    VoxelShape fluidShape = innerContext.getFluidShape(fluidState, BlockGetter.this, pos);
                    fluidHitResult = fluidShape.clip(start, end, pos);
                    e = fluidHitResult == null ? Double.MAX_VALUE : innerContext.getFrom().distanceToSqr(fluidHitResult.getLocation());
                }

                return d <= e ? blockHitResult : fluidHitResult;
            }

            private BlockState getBlock(final LevelReader level, final BlockPos blockPos) {
                if (level.isOutsideBuildHeight(blockPos.getY())) {
                    return Blocks.VOID_AIR.defaultBlockState();
                }

                int chunkX = net.caffeinemc.mods.lithium.common.util.Pos.ChunkCoord.fromBlockCoord(blockPos.getX());
                int chunkZ = net.caffeinemc.mods.lithium.common.util.Pos.ChunkCoord.fromBlockCoord(blockPos.getZ());
                // Avoid calling into the chunk manager as much as possible by managing the current chunk locally
                if (this.chunkX != chunkX || this.chunkZ != chunkZ) {
                    this.chunk = level.getChunk(chunkX, chunkZ);
                    this.chunkX = chunkX;
                    this.chunkZ = chunkZ;
                }

                net.minecraft.world.level.chunk.ChunkAccess chunk = this.chunk;
                // If the chunk is missing or out of bounds, assume that it is air
                if (chunk != null) {
                    // Operate directly on chunk sections to avoid interacting with BlockPos
                    net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSections()[
                        net.caffeinemc.mods.lithium.common.util.Pos.SectionYIndex.fromBlockCoord(chunk, blockPos.getY())
                    ];
                    // If the section doesn't exist or is empty, assume that the block is air
                    if (section != null && !section.hasOnlyAir()) {
                        return section.getBlockState(blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
                    }
                }

                return Blocks.AIR.defaultBlockState();
            }
        };
    }

    default @Nullable BlockHitResult clipWithInteractionOverride(
        final Vec3 from, final Vec3 to, final BlockPos pos, final VoxelShape blockShape, final BlockState blockState
    ) {
        BlockHitResult result = blockShape.clip(from, to, pos);
        if (result != null) {
            BlockHitResult hitOverride = blockState.getInteractionShape(this, pos).clip(from, to, pos);
            if (hitOverride != null && hitOverride.getLocation().subtract(from).lengthSqr() < result.getLocation().subtract(from).lengthSqr()) {
                return result.withDirection(hitOverride.getDirection());
            }
        }

        return result;
    }

    default double getBlockFloorHeight(final VoxelShape blockShape, final Supplier<VoxelShape> belowBlockShape) {
        if (!blockShape.isEmpty()) {
            return blockShape.max(Direction.Axis.Y);
        }

        double belowFloor = belowBlockShape.get().max(Direction.Axis.Y);
        return belowFloor >= 1.0 ? belowFloor - 1.0 : Double.NEGATIVE_INFINITY;
    }

    default double getBlockFloorHeight(final BlockPos pos) {
        return this.getBlockFloorHeight(this.getBlockState(pos).getCollisionShape(this, pos), () -> {
            BlockPos below = pos.below();
            return this.getBlockState(below).getCollisionShape(this, below);
        });
    }

    static <T, C> T traverseBlocks(
        final Vec3 from, final Vec3 to, final C context, final BiFunction<C, BlockPos, @Nullable T> consumer, final Function<C, T> missFactory
    ) {
        if (from.equals(to)) {
            return missFactory.apply(context);
        }

        double toX = Mth.lerp(-1.0E-7, to.x, from.x);
        double toY = Mth.lerp(-1.0E-7, to.y, from.y);
        double toZ = Mth.lerp(-1.0E-7, to.z, from.z);
        double fromX = Mth.lerp(-1.0E-7, from.x, to.x);
        double fromY = Mth.lerp(-1.0E-7, from.y, to.y);
        double fromZ = Mth.lerp(-1.0E-7, from.z, to.z);
        int currentBlockX = Mth.floor(fromX);
        int currentBlockY = Mth.floor(fromY);
        int currentBlockZ = Mth.floor(fromZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(currentBlockX, currentBlockY, currentBlockZ);
        T first = consumer.apply(context, pos);
        if (first != null) {
            return first;
        }

        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        int signX = Mth.sign(dx);
        int signY = Mth.sign(dy);
        int signZ = Mth.sign(dz);
        double tDeltaX = signX == 0 ? Double.MAX_VALUE : signX / dx;
        double tDeltaY = signY == 0 ? Double.MAX_VALUE : signY / dy;
        double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / dz;
        double tX = tDeltaX * (signX > 0 ? 1.0 - Mth.frac(fromX) : Mth.frac(fromX));
        double tY = tDeltaY * (signY > 0 ? 1.0 - Mth.frac(fromY) : Mth.frac(fromY));
        double tZ = tDeltaZ * (signZ > 0 ? 1.0 - Mth.frac(fromZ) : Mth.frac(fromZ));

        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    currentBlockX += signX;
                    tX += tDeltaX;
                } else {
                    currentBlockZ += signZ;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                currentBlockY += signY;
                tY += tDeltaY;
            } else {
                currentBlockZ += signZ;
                tZ += tDeltaZ;
            }

            T result = consumer.apply(context, pos.set(currentBlockX, currentBlockY, currentBlockZ));
            if (result != null) {
                return result;
            }
        }

        return missFactory.apply(context);
    }

    static boolean forEachBlockIntersectedBetween(final Vec3 from, final Vec3 to, final AABB aabbAtTarget, final BlockGetter.BlockStepVisitor visitor) {
        Vec3 travel = to.subtract(from);
                // MODIFIED for porting: was VFP movement.constants MixinBlockGetter (@ModifyExpressionValue <=1_21_7 uses 0.99999)
        float vfp$epsilon = 1.0E-5F;
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_21_7)) {
            vfp$epsilon = 0.99999F;
        }
        if (travel.lengthSqr() < Mth.square(vfp$epsilon)) {
            for (BlockPos blockPos : BlockPos.betweenClosed(aabbAtTarget)) {
                if (!visitor.visit(blockPos, 0)) {
                    return false;
                }
            }

            return true;
        } else {
            LongSet visitedBlocks = new LongOpenHashSet();

            for (BlockPos blockPos : BlockPos.betweenCornersInDirection(aabbAtTarget.move(travel.scale(-1.0)), travel)) {
                if (!visitor.visit(blockPos, 0)) {
                    return false;
                }

                visitedBlocks.add(blockPos.asLong());
            }

            int iterations = addCollisionsAlongTravel(visitedBlocks, travel, aabbAtTarget, visitor);
            if (iterations < 0) {
                return false;
            }

            for (BlockPos blockPos : BlockPos.betweenCornersInDirection(aabbAtTarget, travel)) {
                if (visitedBlocks.add(blockPos.asLong()) && !visitor.visit(blockPos, iterations + 1)) {
                    return false;
                }
            }

            return true;
        }
    }

    private static int addCollisionsAlongTravel(
        final LongSet visitedBlocks, final Vec3 deltaMove, final AABB aabbAtTarget, final BlockGetter.BlockStepVisitor visitor
    ) {
        double boxSizeX = aabbAtTarget.getXsize();
        double boxSizeY = aabbAtTarget.getYsize();
        double boxSizeZ = aabbAtTarget.getZsize();
        Vec3i cornerDir = getFurthestCorner(deltaMove);
        Vec3 toCenter = aabbAtTarget.getCenter();
        Vec3 toCorner = new Vec3(
            toCenter.x() + boxSizeX * 0.5 * cornerDir.getX(),
            toCenter.y() + boxSizeY * 0.5 * cornerDir.getY(),
            toCenter.z() + boxSizeZ * 0.5 * cornerDir.getZ()
        );
        Vec3 fromCorner = toCorner.subtract(deltaMove);
        int cornerVisitedBlockX = Mth.floor(fromCorner.x);
        int cornerVisitedBlockY = Mth.floor(fromCorner.y);
        int cornerVisitedBlockZ = Mth.floor(fromCorner.z);
        int signX = Mth.sign(deltaMove.x);
        int signY = Mth.sign(deltaMove.y);
        int signZ = Mth.sign(deltaMove.z);
        double tDeltaX = signX == 0 ? Double.MAX_VALUE : signX / deltaMove.x;
        double tDeltaY = signY == 0 ? Double.MAX_VALUE : signY / deltaMove.y;
        double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / deltaMove.z;
        double tX = tDeltaX * (signX > 0 ? 1.0 - Mth.frac(fromCorner.x) : Mth.frac(fromCorner.x));
        double tY = tDeltaY * (signY > 0 ? 1.0 - Mth.frac(fromCorner.y) : Mth.frac(fromCorner.y));
        double tZ = tDeltaZ * (signZ > 0 ? 1.0 - Mth.frac(fromCorner.z) : Mth.frac(fromCorner.z));
        int iterations = 0;

        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    cornerVisitedBlockX += signX;
                    tX += tDeltaX;
                } else {
                    cornerVisitedBlockZ += signZ;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                cornerVisitedBlockY += signY;
                tY += tDeltaY;
            } else {
                cornerVisitedBlockZ += signZ;
                tZ += tDeltaZ;
            }

            Optional<Vec3> hitPointOpt = AABB.clip(
                cornerVisitedBlockX,
                cornerVisitedBlockY,
                cornerVisitedBlockZ,
                cornerVisitedBlockX + 1,
                cornerVisitedBlockY + 1,
                cornerVisitedBlockZ + 1,
                fromCorner,
                toCorner
            );
            if (!hitPointOpt.isEmpty()) {
                iterations++;
                Vec3 hitPoint = hitPointOpt.get();
                double cornerHitX = Mth.clamp(hitPoint.x, cornerVisitedBlockX + 1.0E-5F, cornerVisitedBlockX + 1.0 - 1.0E-5F);
                double cornerHitY = Mth.clamp(hitPoint.y, cornerVisitedBlockY + 1.0E-5F, cornerVisitedBlockY + 1.0 - 1.0E-5F);
                double cornerHitZ = Mth.clamp(hitPoint.z, cornerVisitedBlockZ + 1.0E-5F, cornerVisitedBlockZ + 1.0 - 1.0E-5F);
                int oppositeCornerX = Mth.floor(cornerHitX - boxSizeX * cornerDir.getX());
                int oppositeCornerY = Mth.floor(cornerHitY - boxSizeY * cornerDir.getY());
                int oppositeCornerZ = Mth.floor(cornerHitZ - boxSizeZ * cornerDir.getZ());
                int currentIteration = iterations;

                for (BlockPos pos : BlockPos.betweenCornersInDirection(
                    cornerVisitedBlockX, cornerVisitedBlockY, cornerVisitedBlockZ, oppositeCornerX, oppositeCornerY, oppositeCornerZ, deltaMove
                )) {
                    if (visitedBlocks.add(pos.asLong()) && !visitor.visit(pos, currentIteration)) {
                        return -1;
                    }
                }
            }
        }

        return iterations;
    }

    private static Vec3i getFurthestCorner(final Vec3 direction) {
        double xDot = Math.abs(Vec3.X_AXIS.dot(direction));
        double yDot = Math.abs(Vec3.Y_AXIS.dot(direction));
        double zDot = Math.abs(Vec3.Z_AXIS.dot(direction));
        int xSign = direction.x >= 0.0 ? 1 : -1;
        int ySign = direction.y >= 0.0 ? 1 : -1;
        int zSign = direction.z >= 0.0 ? 1 : -1;
        if (xDot <= yDot && xDot <= zDot) {
            return new Vec3i(-xSign, -zSign, ySign);
        } else {
            return yDot <= zDot ? new Vec3i(zSign, -ySign, -xSign) : new Vec3i(-ySign, xSign, -zSign);
        }
    }

    @FunctionalInterface
    interface BlockStepVisitor {
        boolean visit(BlockPos pos, int iteration);
    }
}