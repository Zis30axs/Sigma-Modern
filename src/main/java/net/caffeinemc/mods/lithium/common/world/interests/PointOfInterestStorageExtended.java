package net.caffeinemc.mods.lithium.common.world.interests;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.border.WorldBorder;

import java.util.Collection;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public interface PointOfInterestStorageExtended {
    /**
     * An optimized function for finding the nearest (L2 distance) point inside a square radius. This
     * function iterates through chunks from the center of the search radius outwards, which can speed up searches when
     * the origin is nearby to a valid point of interest.
     *
     * @param pos                The origin point of the search volume
     * @param radius             The radius of the search volume
     * @param type               The type predicate to filter points by early on so they are not passed to {@param predicate}
     * @param status             The required status of points
     * @param afterSortPredicate The point predicate to filter points by once they are sorted
     * @param worldBorder        The world border the POI must be inside.
     * @return Minimal element wrt. to {@link PoiOrdering.L2ThenMinYThenInSquare#INSTANCE} that is within spherical radius and world border
     */
    Optional<PoiRecord> lithium$findNearestForPortalLogic(BlockPos pos, int radius, Holder<PoiType> type, PoiManager.Occupancy status,
                                                                Predicate<PoiRecord> afterSortPredicate, WorldBorder worldBorder);

    Optional<BlockPos> lithium$takeAt(Predicate<Holder<PoiType>> typeFilter, BiPredicate<Holder<PoiType>, BlockPos> biPredicate, BlockPos blockPos);

    Collection<Pair<Holder<PoiType>, BlockPos>> lithium$getNClosestFirstWithType(Predicate<Holder<PoiType>> typeFilter, Predicate<BlockPos> posFilter, BlockPos center, int radius, PoiManager.Occupancy status, long limit);
}
