package net.minecraft.world.level;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult; // MODIFIED for porting: lithium world.explosions.entity_raycast
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * MODIFIED for porting: lithium world.explosions.block_raycast (+ .skip_air) ServerExplosionMixin and
 * world.explosions.entity_raycast ServerExplosionMixin. Block ray casting is rewritten to avoid a BlockPos allocation per
 * ray step, to keep the current chunk locally and to cache block states plus explosion resistances in a direct-mapped
 * cache; air blocks are skipped where that cannot be observed.
 * Original block-raycast implementation by JellySquid, with later work by 2No2Name, jcw780 and pwouik; entity ray casting
 * by Crosby.
 */
public class ServerExplosion implements Explosion, net.caffeinemc.mods.lithium.common.explosion.LithiumExplosion {
    // ---- lithium world.explosions.block_raycast state ----
    /**
     * Size of the direct-mapped cache. 512 entries x (8 + 4 + ref) bytes is roughly 10 KB and should fit into L1.
     * 8-9 bits measured best for TNT in a synthetic world; 9 is used since some explosions are larger than TNT.
     */
    private static final int LITHIUM_DIRECT_CACHE_BITS = 9;
    private static final int LITHIUM_DIRECT_CACHE_SIZE = 1 << LITHIUM_DIRECT_CACHE_BITS;
    private static final int LITHIUM_DIRECT_CACHE_MASK = LITHIUM_DIRECT_CACHE_SIZE - 1;
    // Kept in a thread local to avoid allocating the arrays per explosion (~5% gain)
    private static final ThreadLocal<net.caffeinemc.mods.lithium.common.explosion.DirectMappedExplosionBlockCache> LITHIUM_BLOCK_CACHE_TL =
        ThreadLocal.withInitial(() -> new net.caffeinemc.mods.lithium.common.explosion.DirectMappedExplosionBlockCache(LITHIUM_DIRECT_CACHE_SIZE));

    // The cached mutable block position used during block traversal.
    private final BlockPos.MutableBlockPos lithium$cachedPos = new BlockPos.MutableBlockPos();
    // The chunk coordinates of, and the chunk belonging to, the most recently stepped through block.
    private int lithium$prevChunkX = Integer.MIN_VALUE;
    private int lithium$prevChunkZ = Integer.MIN_VALUE;
    private net.minecraft.world.level.chunk.@Nullable ChunkAccess lithium$prevChunk;
    // Vanilla reports the number of exploded blocks, which the air-block optimization would otherwise change. Different
    // rays traverse the same blocks, so the skipped air positions have to be deduplicated to count them.
    private it.unimi.dsi.fastutil.longs.@Nullable LongOpenHashSet lithium$explodedAirPositions;
    /**
     * Direct-mapped cache. Tags are packed block positions so hash collisions are handled correctly. The tag
     * {@link Long#MIN_VALUE} means "no cached value"; it corresponds to a position more than 3 million blocks outside the
     * world border, and {@code lithium$performRayCast} cuts off at the border, so no sentinel check is needed on lookup.
     */
    private long[] lithium$directMappedTags;
    private BlockState[] lithium$directMappedStates;
    private float[] lithium$directMappedResistances;
    private int lithium$bottomY;
    private int lithium$topY;
    /**
     * Whether the explosion cares about air blocks. >= 1 means air blocks need not be added to the destroyed set; 2 means
     * the total exploded block count is unused as well, so the skipped air blocks need not even be counted.
     */
    private byte lithium$skipAirBlocks;

    @Override
    public void lithium$setSkipAir() {
        this.lithium$skipAirBlocks = 1;
    }

    @Override
    public boolean lithium$isSkippingAir() {
        return this.lithium$skipAirBlocks != 0;
    }

    @Override
    public void lithium$setSkipAirWithoutCounting() {
        if (this.lithium$isSkippingAir()) {
            this.lithium$skipAirBlocks = 2;
        }
    }

    private void lithium$initCaches() {
        net.caffeinemc.mods.lithium.common.explosion.DirectMappedExplosionBlockCache arrays = LITHIUM_BLOCK_CACHE_TL.get();
        this.lithium$directMappedTags = arrays.directMappedTags();
        java.util.Arrays.fill(this.lithium$directMappedTags, Long.MIN_VALUE);
        this.lithium$directMappedStates = arrays.directMappedStates();
        this.lithium$directMappedResistances = arrays.directMappedResistances();
        if (this.lithium$skipAirBlocks == 1) {
            this.lithium$explodedAirPositions = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        }
    }

    private static int lithium$posToCacheIndex(final long posLong) {
        return (int)it.unimi.dsi.fastutil.HashCommon.mix(posLong) & LITHIUM_DIRECT_CACHE_MASK;
    }

    private void lithium$cacheBlock(final long posLong, final BlockState blockState, final float totalResistance) {
        int index = lithium$posToCacheIndex(posLong);
        this.lithium$directMappedTags[index] = posLong;
        this.lithium$directMappedResistances[index] = totalResistance;
        this.lithium$directMappedStates[index] = blockState;
    }

    private int lithium$getCacheHitIndex(final long posLong) {
        int index = lithium$posToCacheIndex(posLong);
        return this.lithium$directMappedTags[index] == posLong ? index : -1;
    }

    private void lithium$performRayCast(
        final net.minecraft.util.RandomSource random,
        final double vecX,
        final double vecY,
        final double vecZ,
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet touched
    ) {
        double dist = Math.sqrt(vecX * vecX + vecY * vecY + vecZ * vecZ);
        double normX = vecX / dist * 0.3;
        double normY = vecY / dist * 0.3;
        double normZ = vecZ / dist * 0.3;
        float strength = this.radius * (0.7F + random.nextFloat() * 0.6F);
        double stepX = this.center.x();
        double stepY = this.center.y();
        double stepZ = this.center.z();
        int prevX = Integer.MIN_VALUE;
        int prevY = Integer.MIN_VALUE;
        int prevZ = Integer.MIN_VALUE;
        float prevResistance = 0.0F;
        int boundMinY = this.lithium$bottomY;
        int boundMaxY = this.lithium$topY;

        // Step through the ray until it is finally stopped
        while (strength > 0.0F) {
            int blockX = Mth.floor(stepX);
            int blockY = Mth.floor(stepY);
            int blockZ = Mth.floor(stepZ);
            float resistance;
            // Check whether we actually moved into a new block this step. Because of how the rays are stepped through, the
            // same block position is over-sampled; changing that would change aliasing and sampling, which is not
            // acceptable here, so the previous result is simply reused.
            if (prevX != blockX || prevY != blockY || prevZ != blockZ) {
                if (blockY < boundMinY
                    || blockY > boundMaxY
                    || blockX < -30000000
                    || blockZ < -30000000
                    || blockX >= 30000000
                    || blockZ >= 30000000) {
                    return;
                }

                resistance = this.lithium$traverseBlock(strength, blockX, blockY, blockZ, touched);
                prevX = blockX;
                prevY = blockY;
                prevZ = blockZ;
                prevResistance = resistance;
            } else {
                resistance = prevResistance;
            }

            strength -= resistance;
            // Apply a constant fall-off
            strength -= 0.22500001F;
            stepX += normX;
            stepY += normY;
            stepZ += normZ;
        }
    }

    /**
     * Called for every step made by a ray being cast by an explosion.
     *
     * @return the resistance of the current block space to the ray
     */
    private float lithium$traverseBlock(
        final float strength, final int blockX, final int blockY, final int blockZ, final it.unimi.dsi.fastutil.longs.LongOpenHashSet touched
    ) {
        long posLong = BlockPos.asLong(blockX, blockY, blockZ);
        // Use the cached blast resistance and block state
        int index = this.lithium$getCacheHitIndex(posLong);
        if (index >= 0) {
            float cachedResistance = this.lithium$directMappedResistances[index];
            this.lithium$tryMarkBlockForDestruction(
                strength, cachedResistance, posLong, this.lithium$directMappedStates[index], blockX, blockY, blockZ, touched
            );
            return cachedResistance;
        }

        BlockPos pos = this.lithium$cachedPos.set(blockX, blockY, blockZ);
        int chunkX = net.caffeinemc.mods.lithium.common.util.Pos.ChunkCoord.fromBlockCoord(blockX);
        int chunkZ = net.caffeinemc.mods.lithium.common.util.Pos.ChunkCoord.fromBlockCoord(blockZ);
        // Avoid calling into the chunk manager as much as possible by managing the current chunk locally
        if (this.lithium$prevChunkX != chunkX || this.lithium$prevChunkZ != chunkZ) {
            this.lithium$prevChunk = this.level.getChunk(chunkX, chunkZ);
            this.lithium$prevChunkX = chunkX;
            this.lithium$prevChunkZ = chunkZ;
        }

        net.minecraft.world.level.chunk.ChunkAccess chunk = this.lithium$prevChunk;
        BlockState blockState = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        float totalResistance = 0.0F;
        Optional<Float> blastResistance;
        labelGetBlastResistance:
        {
            // If the chunk is missing or out of bounds, assume that it is air
            if (chunk != null) {
                // Operate directly on chunk sections to avoid interacting with BlockPos
                net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSections()[
                    net.minecraft.core.SectionPos.blockToSectionCoord(blockY) - net.minecraft.core.SectionPos.blockToSectionCoord(this.lithium$bottomY)
                ];
                // If the section doesn't exist or is empty, assume that the block is air
                if (section != null && !section.hasOnlyAir()) {
                    blockState = section.getBlockState(blockX & 15, blockY & 15, blockZ & 15);
                    // Air can have neither fluid nor resistance, so leave early
                    if (blockState.getBlock() != net.minecraft.world.level.block.Blocks.AIR) {
                        // Asking the block state for its fluid is exactly what the container call would do, minus a second
                        // block state lookup.
                        FluidState fluidState = blockState.getFluidState();
                        blastResistance = this.damageCalculator.getBlockExplosionResistance(this, this.level, pos, blockState, fluidState);
                        break labelGetBlastResistance;
                    }
                }
            }

            blastResistance = this.damageCalculator
                .getBlockExplosionResistance(
                    this,
                    this.level,
                    pos,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState()
                );
        }

        // Calculate how much this block resists the explosion's ray
        if (blastResistance.isPresent()) {
            totalResistance = (blastResistance.get() + 0.3F) * 0.3F;
        }

        // Cache the block state and the resistance for other rays hitting the same position
        this.lithium$cacheBlock(posLong, blockState, totalResistance);
        this.lithium$tryMarkBlockForDestruction(strength, totalResistance, posLong, blockState, blockX, blockY, blockZ, touched);
        return totalResistance;
    }

    private void lithium$tryMarkBlockForDestruction(
        final float strength,
        final float totalResistance,
        final long posLong,
        final BlockState blockState,
        final int blockX,
        final int blockY,
        final int blockZ,
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet touched
    ) {
        float reducedStrength = strength - totalResistance;
        if (reducedStrength > 0.0F) {
            if (this.lithium$skipAirBlocks != 0 && blockState.isAir()) {
                if (this.lithium$explodedAirPositions != null) {
                    this.lithium$explodedAirPositions.add(posLong);
                }

                return;
            }

            BlockPos pos = this.lithium$cachedPos.set(blockX, blockY, blockZ);
            if (this.damageCalculator.shouldBlockExplode(this, this.level, pos, blockState, reducedStrength)) {
                touched.add(posLong);
            }
        }
    }

    private static final ExplosionDamageCalculator EXPLOSION_DAMAGE_CALCULATOR = new ExplosionDamageCalculator();
    private static final int MAX_DROPS_PER_COMBINED_STACK = 16;
    private static final float LARGE_EXPLOSION_RADIUS = 2.0F;
    private final boolean fire;
    private final Explosion.BlockInteraction blockInteraction;
    private final ServerLevel level;
    private final Vec3 center;
    private final @Nullable Entity source;
    private final float radius;
    private final DamageSource damageSource;
    private final ExplosionDamageCalculator damageCalculator;
    private final Map<Player, Vec3> hitPlayers = new HashMap<>();

    public ServerExplosion(
        final ServerLevel level,
        final @Nullable Entity source,
        final @Nullable DamageSource damageSource,
        final @Nullable ExplosionDamageCalculator damageCalculator,
        final Vec3 center,
        final float radius,
        final boolean fire,
        final Explosion.BlockInteraction blockInteraction
    ) {
        this.level = level;
        this.source = source;
        this.radius = radius;
        this.center = center;
        this.fire = fire;
        this.blockInteraction = blockInteraction;
        this.damageSource = damageSource == null ? level.damageSources().explosion(this) : damageSource;
        this.damageCalculator = damageCalculator == null ? this.makeDamageCalculator(source) : damageCalculator;
        // MODIFIED for porting: lithium world.explosions.block_raycast ServerExplosionMixin#init
        this.lithium$bottomY = this.level.getMinY();
        this.lithium$topY = this.level.getMaxY();
        // MODIFIED for porting: lithium world.explosions.block_raycast.skip_air ServerExplosionMixin#init. Air blocks only
        // matter for the explosion when fire should be created inside them, or when block updates caused by the explosion
        // would replace them (which is the non-vanilla behaviour this optimization accepts).
        // To work around exploding end portals placed by an explosion destroying the end crystal that is currently
        // respawning the ender dragon, the optimization is only applied far enough away from the exit portal.
        if (!this.fire
            && this.level.dimension() == Level.END
            && this.level.dimensionTypeRegistration().is(net.minecraft.world.level.dimension.BuiltinDimensionTypes.END)) {
            float overestimatedExplosionRange = 8 + (int)(6.0F * this.radius);
            if (overestimatedExplosionRange > Math.abs(this.center.x - 0) && overestimatedExplosionRange > Math.abs(this.center.z - 0)) {
                // Could also check whether the dragon fight is in its respawn phase, but that needs even more hooks.
                return;
            }
        }

        this.lithium$setSkipAir();
    }

    // MODIFIED for porting: lithium world.explosions.entity_raycast ServerExplosionMixin constants
    @SuppressWarnings("DataFlowIssue")
    private static final BlockHitResult LITHIUM_MISS = BlockHitResult.miss(null, null, null);
    private static final BlockHitResult LITHIUM_DUMMY_HIT =
        new BlockHitResult(Vec3.ZERO, net.minecraft.core.Direction.NORTH, BlockPos.ZERO, false);

    /**
     * MODIFIED for porting: was lithium's world.explosions.entity_raycast ServerExplosionMixin#blockHitFactory. Specialized
     * version of the factory in {@link BlockGetter#clip(ClipContext)}.
     */
    private static java.util.function.BiFunction<ClipContext, BlockPos, BlockHitResult> lithium$blockHitFactory(final Entity entity) {
        return new java.util.function.BiFunction<>() {
            private final Level level = entity.level();
            private final net.caffeinemc.mods.lithium.common.explosion.DirectMappedPos2AABBsCache cache = net.caffeinemc.mods.lithium.common.explosion.DirectMappedPos2AABBsCache.BLOCK_CACHE_TL.get();
            private int chunkX = Integer.MIN_VALUE;
            private int chunkZ = Integer.MIN_VALUE;
            private net.minecraft.world.level.chunk.ChunkAccess chunk = null;

            {
                this.cache.invalidate();
            }

            @Override
            public BlockHitResult apply(final ClipContext clipContext, final BlockPos blockPos) {
                long posLong = blockPos.asLong();
                AABB[] aabbs = this.cache.getEntry(posLong);
                if (aabbs == null) {
                    BlockState state = this.getBlock(this.level, blockPos);
                    net.minecraft.world.phys.shapes.VoxelShape collisionShape = state.getCollisionShape(
                        this.level, blockPos, ((net.caffeinemc.mods.lithium.common.world.explosions.ClipContextAccess)clipContext).lithium$getCollisionContext()
                    );
                    aabbs = collisionShape.isEmpty() ? net.caffeinemc.mods.lithium.common.util.ArrayConstants.EMPTY_AABBS : collisionShape.toAabbs().toArray(AABB[]::new);
                    this.cache.cacheEntry(aabbs, posLong);
                }

                boolean wasHit = aabbs.length > 0
                    && net.caffeinemc.mods.lithium.common.explosion.ExplosionEntityRays.doesRayHitOffsetAABBVolumes(aabbs, blockPos, clipContext.getFrom(), clipContext.getTo());
                return wasHit ? LITHIUM_DUMMY_HIT : null;
            }

            // Code duplicated from BlockGetter#lithium$blockHitFactory
            private BlockState getBlock(final LevelReader world, final BlockPos blockPos) {
                if (world.isOutsideBuildHeight(blockPos.getY())) {
                    return net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState();
                }

                int chunkX = net.caffeinemc.mods.lithium.common.util.Pos.ChunkCoord.fromBlockCoord(blockPos.getX());
                int chunkZ = net.caffeinemc.mods.lithium.common.util.Pos.ChunkCoord.fromBlockCoord(blockPos.getZ());
                if (this.chunkX != chunkX || this.chunkZ != chunkZ) {
                    this.chunk = world.getChunk(chunkX, chunkZ);
                    this.chunkX = chunkX;
                    this.chunkZ = chunkZ;
                }

                net.minecraft.world.level.chunk.ChunkAccess chunk = this.chunk;
                if (chunk != null) {
                    net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSections()[
                        net.caffeinemc.mods.lithium.common.util.Pos.SectionYIndex.fromBlockCoord(chunk, blockPos.getY())
                    ];
                    if (section != null && !section.hasOnlyAir()) {
                        return section.getBlockState(blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
                    }
                }

                return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }
        };
    }

    private ExplosionDamageCalculator makeDamageCalculator(final @Nullable Entity source) {
        return source == null ? EXPLOSION_DAMAGE_CALCULATOR : new EntityBasedExplosionDamageCalculator(source);
    }

    /**
     * MODIFIED for porting: lithium world.explosions.entity_raycast ServerExplosionMixin. A single ClipContext is reused for
     * all rays (only its start position changes), the miss result is a shared constant, and the per-block callback is a
     * specialized one that caches the collision AABBs per block position and skips fluid handling and hit-direction
     * computation entirely.
     */
    public static float getSeenPercent(final Vec3 center, final Entity entity) {
        java.util.function.BiFunction<ClipContext, BlockPos, BlockHitResult> lithium$hitFactory = lithium$blockHitFactory(entity);
        ClipContext lithium$context = null;
        AABB bb = entity.getBoundingBox();
        double xs = 1.0 / ((bb.maxX - bb.minX) * 2.0 + 1.0);
        double ys = 1.0 / ((bb.maxY - bb.minY) * 2.0 + 1.0);
        double zs = 1.0 / ((bb.maxZ - bb.minZ) * 2.0 + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (!(xs < 0.0) && !(ys < 0.0) && !(zs < 0.0)) {
            int hits = 0;
            int count = 0;

            for (double xx = 0.0; xx <= 1.0; xx += xs) {
                for (double yy = 0.0; yy <= 1.0; yy += ys) {
                    for (double zz = 0.0; zz <= 1.0; zz += zs) {
                        double x = Mth.lerp(xx, bb.minX, bb.maxX);
                        double y = Mth.lerp(yy, bb.minY, bb.maxY);
                        double z = Mth.lerp(zz, bb.minZ, bb.maxZ);
                        Vec3 from = new Vec3(x + xOffset, y, z + zOffset);
                        // MODIFIED for porting: lithium entity_raycast ServerExplosionMixin#reuseClipContext
                        if (lithium$context == null) {
                            lithium$context = new ClipContext(from, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity);
                        } else {
                            ((net.caffeinemc.mods.lithium.common.world.explosions.ClipContextAccess)lithium$context).lithium$setFrom(from);
                        }

                        // MODIFIED for porting: lithium entity_raycast ServerExplosionMixin#simplifyRaycast
                        if (BlockGetter.traverseBlocks(
                                lithium$context.getFrom(), lithium$context.getTo(), lithium$context, lithium$hitFactory, ctx -> LITHIUM_MISS
                            )
                            .getType()
                            == HitResult.Type.MISS) {
                            hits++;
                        }

                        count++;
                    }
                }
            }

            return (float)hits / count;
        } else {
            return 0.0F;
        }
    }

    @Override
    public float radius() {
        return this.radius;
    }

    @Override
    public Vec3 center() {
        return this.center;
    }

    /**
     * MODIFIED for porting: lithium world.explosions.block_raycast ServerExplosionMixin. Upstream skips the whole vanilla
     * ray loop (by zeroing its bound and replacing the HashSet with a dummy) and fills the returned list from its own
     * implementation injected at RETURN. Using integer-encoded block positions avoids allocating a BlockPos for every step
     * of every ray, which removes essentially all allocations of this method.
     */
    private List<BlockPos> calculateExplodedPositions() {
        List<BlockPos> affectedBlocks = new ObjectArrayList<>();
        this.lithium$initCaches();
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet touched = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(0);
        final net.minecraft.util.RandomSource random = this.level.getRandom();

        // Explosions work by casting many rays through the world from the origin of the explosion
        for (int rayX = 0; rayX < 16; rayX++) {
            boolean xPlane = rayX == 0 || rayX == 15;
            double vecX = (float)rayX / 15.0F * 2.0F - 1.0F;

            for (int rayY = 0; rayY < 16; rayY++) {
                boolean yPlane = rayY == 0 || rayY == 15;
                double vecY = (float)rayY / 15.0F * 2.0F - 1.0F;
                // Rays are only fired from the surface of the origin volume. This saves 2744 (14^3) iterations without
                // changing the order.
                int zIncrement = xPlane || yPlane ? 1 : 15;

                for (int rayZ = 0; rayZ < 16; rayZ += zIncrement) {
                    double vecZ = (float)rayZ / 15.0F * 2.0F - 1.0F;
                    this.lithium$performRayCast(random, vecX, vecY, vecZ, touched);
                }
            }
        }

        // Rebuild BlockPos objects only for the positions that were actually touched
        it.unimi.dsi.fastutil.longs.LongIterator it = touched.iterator();
        while (it.hasNext()) {
            affectedBlocks.add(BlockPos.of(it.nextLong()));
        }

        return affectedBlocks;
    }

    private void hurtEntities() {
        if (!(this.radius < 1.0E-5F)) {
            float doubleRadius = this.radius * 2.0F;
            int x0 = Mth.floor(this.center.x - doubleRadius - 1.0);
            int x1 = Mth.floor(this.center.x + doubleRadius + 1.0);
            int y0 = Mth.floor(this.center.y - doubleRadius - 1.0);
            int y1 = Mth.floor(this.center.y + doubleRadius + 1.0);
            int z0 = Mth.floor(this.center.z - doubleRadius - 1.0);
            int z1 = Mth.floor(this.center.z + doubleRadius + 1.0);

            for (Entity entity : this.level.getEntities(this.source, new AABB(x0, y0, z0, x1, y1, z1))) {
                if (!entity.ignoreExplosion(this)) {
                    double dist = Math.sqrt(entity.distanceToSqr(this.center)) / doubleRadius;
                    if (!(dist > 1.0)) {
                        Vec3 entityOrigin = entity instanceof PrimedTnt ? entity.position() : entity.getEyePosition();
                        Vec3 direction = entityOrigin.subtract(this.center).normalize();
                        boolean shouldDamageEntity = this.damageCalculator.shouldDamageEntity(this, entity);
                        float knockbackMultiplier = this.damageCalculator.getKnockbackMultiplier(entity);
                        float exposure = !shouldDamageEntity && knockbackMultiplier == 0.0F ? 0.0F : getSeenPercent(this.center, entity);
                        if (shouldDamageEntity) {
                            entity.hurtServer(this.level, this.damageSource, this.damageCalculator.getEntityDamageAmount(this, entity, exposure));
                        }

                        double knockbackResistance = entity instanceof LivingEntity livingEntity
                            ? livingEntity.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)
                            : 0.0;
                        double knockbackPower = (1.0 - dist) * exposure * knockbackMultiplier * (1.0 - knockbackResistance);
                        Vec3 knockback = direction.scale(knockbackPower);
                        entity.push(knockback);
                        if (entity.is(EntityTypeTags.REDIRECTABLE_PROJECTILE) && entity instanceof Projectile projectile) {
                            projectile.setOwner(this.damageSource.getEntity());
                        } else if (entity instanceof Player player && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying)) {
                            this.hitPlayers.put(player, knockback);
                        }

                        entity.onExplosionHit(this.source);
                    }
                }
            }
        }
    }

    private void interactWithBlocks(final List<BlockPos> targetBlocks) {
        List<ServerExplosion.StackCollector> stacks = new ArrayList<>();
        Util.shuffle(targetBlocks, this.level.random);

        for (BlockPos pos : targetBlocks) {
            this.level.getBlockState(pos).onExplosionHit(this.level, pos, this, (stackx, position) -> addOrAppendStack(stacks, stackx, position));
        }

        for (ServerExplosion.StackCollector stack : stacks) {
            Block.popResource(this.level, stack.pos, stack.stack);
        }
    }

    private void createFire(final List<BlockPos> targetBlocks) {
        for (BlockPos pos : targetBlocks) {
            if (this.level.random.nextInt(3) == 0 && this.level.getBlockState(pos).isAir() && this.level.getBlockState(pos.below()).isSolidRender()) {
                this.level.setBlockAndUpdate(pos, BaseFireBlock.getState(this.level, pos));
            }
        }
    }

    public int explode() {
        this.level.gameEvent(this.source, GameEvent.EXPLODE, this.center);
        List<BlockPos> toBlow = this.calculateExplodedPositions();
        this.hurtEntities();
        if (this.interactsWithBlocks()) {
            ProfilerFiller profiler = Profiler.get();
            profiler.push("explosion_blocks");
            this.interactWithBlocks(toBlow);
            profiler.pop();
        }

        if (this.fire) {
            this.createFire(toBlow);
        }

        // MODIFIED for porting: lithium world.explosions.block_raycast ServerExplosionMixin#getExplodedPositionCount - the
        // air blocks that were skipped still have to be reported, so the count matches vanilla.
        return this.lithium$explodedAirPositions != null ? this.lithium$explodedAirPositions.size() + toBlow.size() : toBlow.size();
    }

    private static void addOrAppendStack(final List<ServerExplosion.StackCollector> stacks, final ItemStack stack, final BlockPos pos) {
        for (ServerExplosion.StackCollector stackCollector : stacks) {
            stackCollector.tryMerge(stack);
            if (stack.isEmpty()) {
                return;
            }
        }

        stacks.add(new ServerExplosion.StackCollector(pos, stack));
    }

    private boolean interactsWithBlocks() {
        return this.blockInteraction != Explosion.BlockInteraction.KEEP;
    }

    public Map<Player, Vec3> getHitPlayers() {
        return this.hitPlayers;
    }

    @Override
    public ServerLevel level() {
        return this.level;
    }

    @Override
    public @Nullable LivingEntity getIndirectSourceEntity() {
        return Explosion.getIndirectSourceEntity(this.source);
    }

    @Override
    public @Nullable Entity getDirectSourceEntity() {
        return this.source;
    }

    public DamageSource getDamageSource() {
        return this.damageSource;
    }

    @Override
    public Explosion.BlockInteraction getBlockInteraction() {
        return this.blockInteraction;
    }

    @Override
    public boolean canTriggerBlocks() {
        if (this.blockInteraction != Explosion.BlockInteraction.TRIGGER_BLOCK) {
            return false;
        } else {
            return this.source != null && this.source.is(EntityTypes.BREEZE_WIND_CHARGE) ? this.level.getGameRules().get(GameRules.MOB_GRIEFING) : true;
        }
    }

    @Override
    public boolean shouldAffectBlocklikeEntities() {
        boolean mobGriefingEnabled = this.level.getGameRules().get(GameRules.MOB_GRIEFING);
        boolean isNotWindCharge = this.source == null || !this.source.is(EntityTypes.BREEZE_WIND_CHARGE) && !this.source.is(EntityTypes.WIND_CHARGE);
        return mobGriefingEnabled ? isNotWindCharge : this.blockInteraction.shouldAffectBlocklikeEntities() && isNotWindCharge;
    }

    public boolean isSmall() {
        return this.radius < 2.0F || !this.interactsWithBlocks();
    }

    private static class StackCollector {
        private final BlockPos pos;
        private ItemStack stack;

        private StackCollector(final BlockPos pos, final ItemStack stack) {
            this.pos = pos;
            this.stack = stack;
        }

        public void tryMerge(final ItemStack input) {
            if (ItemEntity.areMergable(this.stack, input)) {
                this.stack = ItemEntity.merge(this.stack, input, 16);
            }
        }
    }
}