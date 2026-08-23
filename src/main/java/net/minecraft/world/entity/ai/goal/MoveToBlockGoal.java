package net.minecraft.world.entity.ai.goal;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.LevelReader;

// MODIFIED for porting: implements lithium's LithiumMoveToBlockGoal (ai.non_poi_block_search MoveToBlockGoalMixin)
public abstract class MoveToBlockGoal extends Goal implements net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.LithiumMoveToBlockGoal {
    private static final int GIVE_UP_TICKS = 1200;
    private static final int STAY_TICKS = 1200;
    private static final int INTERVAL_TICKS = 200;
    protected final PathfinderMob mob;
    public final double speedModifier;
    protected int nextStartTick;
    protected int tryTicks;
    private int maxStayTicks;
    protected BlockPos blockPos = BlockPos.ZERO;
    private boolean reachedTarget;
    private final int searchRange;
    private final int verticalSearchRange;
    protected int verticalSearchStart;

    public MoveToBlockGoal(final PathfinderMob mob, final double speedModifier, final int searchRange) {
        this(mob, speedModifier, searchRange, 1);
    }

    public MoveToBlockGoal(final PathfinderMob mob, final double speedModifier, final int searchRange, final int verticalSearchRange) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.searchRange = searchRange;
        this.verticalSearchStart = 0;
        this.verticalSearchRange = verticalSearchRange;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.nextStartTick > 0) {
            this.nextStartTick--;
            return false;
        } else {
            this.nextStartTick = this.nextStartTick(this.mob);
            return this.findNearestBlock();
        }
    }

    protected int nextStartTick(final PathfinderMob mob) {
        return reducedTickDelay(200 + mob.getRandom().nextInt(200));
    }

    @Override
    public boolean canContinueToUse() {
        return this.tryTicks >= -this.maxStayTicks && this.tryTicks <= 1200 && this.isValidTarget(this.mob.level(), this.blockPos);
    }

    @Override
    public void start() {
        this.moveMobToBlock();
        this.tryTicks = 0;
        this.maxStayTicks = this.mob.getRandom().nextInt(this.mob.getRandom().nextInt(1200) + 1200) + 1200;
    }

    protected void moveMobToBlock() {
        this.mob.getNavigation().moveTo(this.blockPos.getX() + 0.5, this.blockPos.getY() + 1, this.blockPos.getZ() + 0.5, this.speedModifier);
    }

    public double acceptedDistance() {
        return 1.0;
    }

    protected BlockPos getMoveToTarget() {
        return this.blockPos.above();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        BlockPos moveToTarget = this.getMoveToTarget();
        if (!moveToTarget.closerToCenterThan(this.mob.position(), this.acceptedDistance())) {
            this.reachedTarget = false;
            this.tryTicks++;
            if (this.shouldRecalculatePath()) {
                this.mob.getNavigation().moveTo(moveToTarget.getX() + 0.5, moveToTarget.getY(), moveToTarget.getZ() + 0.5, this.speedModifier);
            }
        } else {
            this.reachedTarget = true;
            this.tryTicks--;
        }
    }

    public boolean shouldRecalculatePath() {
        return this.tryTicks % 40 == 0;
    }

    protected boolean isReachedTarget() {
        return this.reachedTarget;
    }

    protected boolean findNearestBlock() {
        int horizontalSearch = this.searchRange;
        int verticalSearch = this.verticalSearchRange;
        BlockPos mobPos = this.mob.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = this.verticalSearchStart; y <= verticalSearch; y = y > 0 ? -y : 1 - y) {
            for (int r = 0; r < horizontalSearch; r++) {
                for (int x = 0; x <= r; x = x > 0 ? -x : 1 - x) {
                    for (int z = x < r && x > -r ? r : 0; z <= r; z = z > 0 ? -z : 1 - z) {
                        pos.setWithOffset(mobPos, x, y - 1, z);
                        if (this.mob.isWithinHome(pos) && this.isValidTarget(this.mob.level(), pos)) {
                            this.blockPos = pos;
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * MODIFIED for porting: was lithium's ai.non_poi_block_search MoveToBlockGoalMixin, by jcw780. [Vanilla Copy] - the
     * chunk aware search order is different, but *SHOULD* result in the same position.
     * <p>
     * MoveToBlockGoal search is quite laggy if a lot of mobs are trying to start it - e.g. Portal Gold Farms. This is because
     * the searched blocks are not POIs and the search range can be massive - 47x7x47 for zombies. During this search both
     * getChunk and getBlockState contribute a large portion of the lag. The implementation below optimizes it by caching the
     * ChunkAccesses and by checking whether the ChunkSection has the target block using ChunkSection#maybeHas.
     * <p>
     * Finds the nearest block matching the predicates. Side effect: the matching block position is stored in the blockPos
     * field.
     *
     * @return Whether a matching block was found.
     */
    @Override
    public boolean lithium$findNearestBlock(
        final java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> requiredBlock,
        final java.util.function.BiPredicate<net.minecraft.world.level.chunk.ChunkAccess, BlockPos.MutableBlockPos> lithium$isValidTarget,
        final boolean shouldChunkLoad
    ) {
        // Center of the search starts 1 block below the mob's block position
        BlockPos center = this.mob.blockPosition().offset(0, -1, 0);
        // Range is +-(searchRange - 1), +-verticalSearchRange, +-(searchRange - 1)
        // Cache ChunkAccesses - getting them is surprisingly expensive - and track whether subchunks have the block
        LevelReader levelReader = this.mob.level();
        net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.CheckAndCacheBlockChecker checker = new net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.CheckAndCacheBlockChecker(
            center, this.searchRange - 1, this.verticalSearchRange, levelReader, requiredBlock, shouldChunkLoad
        );
        it.unimi.dsi.fastutil.longs.LongArrayList sortedChunksMaybeWithBlock =
            new it.unimi.dsi.fastutil.longs.LongArrayList(checker.getChunkSize());
        checker.initializeChunks(sortedChunksMaybeWithBlock::addLast);
        if (checker.shouldStop()) {
            // No chunks with the target block - return early
            return false;
        }

        int minY = net.caffeinemc.mods.lithium.common.util.Pos.BlockCoord.getMinY(levelReader);
        int maxY = net.caffeinemc.mods.lithium.common.util.Pos.BlockCoord.getMaxYInclusive(levelReader);
        // Prefer chunk aware search because it also cuts iterations inside "empty" chunk sections
        if (!checker.hasUnloadedPossibleChunks()) {
            return this.lithium$chunkAwareSearch(center, lithium$isValidTarget, checker, sortedChunksMaybeWithBlock, minY, maxY);
        }

        // Use vanilla search because unordered search may observably alter chunk-loading behavior
        return this.lithium$vanillaOrderSearch(center, lithium$isValidTarget, checker, minY, maxY);
    }

    // MODIFIED for porting: lithium ai.non_poi_block_search MoveToBlockGoalMixin#lithium$vanillaOrderSearch
    private boolean lithium$vanillaOrderSearch(
        final BlockPos center,
        final java.util.function.BiPredicate<net.minecraft.world.level.chunk.ChunkAccess, BlockPos.MutableBlockPos> lithium$isValidTarget,
        final net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.CheckAndCacheBlockChecker checker,
        final int minY,
        final int maxY
    ) {
        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        int centerY = center.getY();

        for (int layer = this.verticalSearchStart; layer <= this.verticalSearchRange; layer = layer > 0 ? -layer : 1 - layer) {
            int y = centerY + layer;
            // Layer outside of build limit - skip. This is likely to be hit because farms where this lags tend to be built at
            // the world floor.
            if (y < minY || y > maxY) {
                continue;
            }

            for (int ring = 0; ring < this.searchRange; ring++) {
                for (int dX = 0; dX <= ring; dX = dX > 0 ? -dX : 1 - dX) {
                    for (int dZ = dX < ring && dX > -ring ? ring : 0; dZ <= ring; dZ = dZ > 0 ? -dZ : 1 - dZ) {
                        currentPos.setWithOffset(center, dX, layer, dZ);
                        if (this.mob.isWithinHome(currentPos) && checker.checkPosition(currentPos)) {
                            // ChunkAccess is always loaded at this point
                            net.minecraft.world.level.chunk.ChunkAccess chunkAccess = checker.getCachedChunkAccess(currentPos);
                            if (lithium$isValidTarget.test(chunkAccess, currentPos)) {
                                this.blockPos = currentPos;
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    // MODIFIED for porting: lithium ai.non_poi_block_search MoveToBlockGoalMixin#lithium$chunkAwareSearch
    private boolean lithium$chunkAwareSearch(
        final BlockPos center,
        final java.util.function.BiPredicate<net.minecraft.world.level.chunk.ChunkAccess, BlockPos.MutableBlockPos> lithium$isValidTarget,
        final net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.CheckAndCacheBlockChecker checker,
        final it.unimi.dsi.fastutil.longs.LongArrayList sortedChunksMaybeWithBlock,
        final int minY,
        final int maxY
    ) {
        // Sort chunks by lowest sort order - has the earliest searched position. In this search order, the closest point
        // normally is also the closest point in the search.
        sortedChunksMaybeWithBlock.sort(
            (chunkLong0, chunkLong1) -> net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.NonPOISearchDistances.MoveToBlockGoalDistances.getMinimumSortOrderOfChunk(center, chunkLong0)
                - net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.NonPOISearchDistances.MoveToBlockGoalDistances.getMinimumSortOrderOfChunk(center, chunkLong1)
        );
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> requiredBlock = checker.blockStatePredicate;
        int minSectionY = checker.minSectionY;
        BlockPos.MutableBlockPos foundPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();

        // Same layer order as vanilla - saves iterations if targets are found in the first layer
        for (int layer = this.verticalSearchStart; layer <= this.verticalSearchRange; layer = layer > 0 ? -layer : 1 - layer) {
            int y = center.getY() + layer;
            if (y < minY || y > maxY) {
                continue;
            }

            int chunkY = net.minecraft.core.SectionPos.blockToSectionCoord(y);
            int ySectionIndex = chunkY - minSectionY;
            int closestFound = Integer.MAX_VALUE;
            int ringMax = this.searchRange - 1;

            // Iterate through slices of chunks that may have the target blockState
            for (long chunkPos : sortedChunksMaybeWithBlock) {
                int chunkX = net.minecraft.world.level.ChunkPos.getX(chunkPos);
                int chunkZ = net.minecraft.world.level.ChunkPos.getZ(chunkPos);
                // Break since no subsequent chunks can be closer
                if (closestFound < net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.NonPOISearchDistances.MoveToBlockGoalDistances.getMinimumSortOrderOfChunk(center, chunkX, chunkZ)) {
                    break;
                }

                // Skip if the current subchunk does not have the block
                if (!checker.checkCachedSection(chunkX, chunkY, chunkZ)) {
                    continue;
                }

                net.minecraft.world.level.chunk.ChunkAccess chunkAccess = checker.getCachedChunkAccess(chunkPos);
                // If the ChunkSection may have close enough targets, iterate the layer in paletted container (x then z) order
                int chunkBlockX = net.minecraft.core.SectionPos.sectionToBlockCoord(chunkX);
                int xMin = Math.max(center.getX() - ringMax, chunkBlockX);
                int xMax = Math.min(center.getX() + ringMax, chunkBlockX + 15);
                int chunkBlockZ = net.minecraft.core.SectionPos.sectionToBlockCoord(chunkZ);
                int zMin = Math.max(center.getZ() - ringMax, chunkBlockZ);
                int zMax = Math.min(center.getZ() + ringMax, chunkBlockZ + 15);
                net.minecraft.world.level.chunk.LevelChunkSection levelChunkSection = chunkAccess.getSections()[ySectionIndex];

                for (int z = zMin; z <= zMax; z++) {
                    for (int x = xMin; x <= xMax; x++) {
                        int dX = x - center.getX();
                        int dZ = z - center.getZ();
                        int ring = net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.NonPOISearchDistances.MoveToBlockGoalDistances.getRing(dX, dZ);
                        int currentDistance = net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.NonPOISearchDistances.MoveToBlockGoalDistances.getVanillaSortOrderInt(ring, dX, dZ);
                        if (currentDistance < closestFound
                            && this.mob.isWithinHome(currentPos.set(x, y, z))
                            && requiredBlock.test(levelChunkSection.getBlockState(x & 15, y & 15, z & 15))
                            && lithium$isValidTarget.test(chunkAccess, currentPos)) {
                            // Constrain search size when we find a valid target
                            ringMax = ring;
                            xMin = Math.max(center.getX() - ringMax, chunkBlockX);
                            xMax = Math.min(center.getX() + ringMax, chunkBlockX + 15);
                            zMax = Math.min(center.getZ() + ringMax, chunkBlockZ + 15);
                            foundPos.set(x, y, z);
                            closestFound = currentDistance;
                        }
                    }
                }
            }

            if (closestFound < Integer.MAX_VALUE) {
                // Vanilla uses the mutable pos, no need to create an immutable copy
                this.blockPos = foundPos;
                return true;
            }
        }

        return false;
    }

    protected abstract boolean isValidTarget(LevelReader level, BlockPos pos);
}