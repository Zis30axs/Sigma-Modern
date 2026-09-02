package net.minecraft.world.phys.shapes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.common.math.DoubleMath;
import com.google.common.math.IntMath;
import com.mojang.math.OctahedralGroup;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.core.AxisCycle;
import net.minecraft.core.Direction;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Shapes {
    public static final double EPSILON = 1.0E-7;
    public static final double BIG_EPSILON = 1.0E-6;
    // MODIFIED for porting: lithium shapes.specialized_shapes ShapesMixin replaces the three global shapes with
    // specialized implementations that know they consist of exactly one cuboid / one voxel, which makes intersection and
    // penetration tests plain arithmetic. Upstream did this in a static block appended after the vanilla initializers;
    // initializing the fields directly here reaches the same end state without the transient vanilla objects.
    // [VanillaCopy] BLOCK and INFINITY use a single 1x1x1 voxel as neither contains multiple inner cuboids.
    private static final DiscreteVoxelShape FULL_CUBE_VOXELS = Util.make(() -> {
        BitSetDiscreteVoxelShape shape = new BitSetDiscreteVoxelShape(1, 1, 1);
        shape.fill(0, 0, 0);
        return shape;
    });
    private static final VoxelShape BLOCK = new net.caffeinemc.mods.lithium.common.shapes.VoxelShapeSimpleCube(FULL_CUBE_VOXELS, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
    private static final Vec3 BLOCK_CENTER = new Vec3(0.5, 0.5, 0.5);
    // Used in some rare cases to indicate a shape which encompasses the entire world (such as a moving world border)
    public static final VoxelShape INFINITY = new net.caffeinemc.mods.lithium.common.shapes.VoxelShapeSimpleCube(
        FULL_CUBE_VOXELS,
        Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY
    );
    private static final VoxelShape EMPTY = new net.caffeinemc.mods.lithium.common.shapes.VoxelShapeEmpty(new BitSetDiscreteVoxelShape(0, 0, 0));

    public static VoxelShape empty() {
        return EMPTY;
    }

    public static VoxelShape block() {
        return BLOCK;
    }

    public static VoxelShape box(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        if (!(minX > maxX) && !(minY > maxY) && !(minZ > maxZ)) {
            return create(minX, minY, minZ, maxX, maxY, maxZ);
        } else {
            throw new IllegalArgumentException("The min values need to be smaller or equals to the max values");
        }
    }

    /**
     * MODIFIED for porting: lithium shapes.specialized_shapes ShapesMixin#create.
     * Vanilla uses different kinds of VoxelShapes depending on the size and position of the box: a box that is not
     * aligned to 1/8 of a block becomes a two-point ArrayVoxelShape, others become a CubeVoxelShape whose
     * BitSetDiscreteVoxelShape may have a higher resolution (1-3 bits per axis) and therefore internal collision layers.
     * Lithium returns its specialized equivalents, which represent the same shapes but compare much faster.
     */
    public static VoxelShape create(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        if (maxX - minX < 1.0E-7 || maxY - minY < 1.0E-7 || maxZ - minZ < 1.0E-7) {
            return EMPTY;
        }

        int xBits;
        int yBits;
        int zBits;
        if ((xBits = findBits(minX, maxX)) < 0 || (yBits = findBits(minY, maxY)) < 0 || (zBits = findBits(minZ, maxZ)) < 0) {
            // vanilla uses ArrayVoxelShape here without any rounding of the coordinates
            return new net.caffeinemc.mods.lithium.common.shapes.VoxelShapeSimpleCube(FULL_CUBE_VOXELS, minX, minY, minZ, maxX, maxY, maxZ);
        }

        if (xBits == 0 && yBits == 0 && zBits == 0) {
            return BLOCK;
        }

        // vanilla would use a CubeVoxelShape with a BitSetDiscreteVoxelShape of resolution xBits/yBits/zBits here
        return new net.caffeinemc.mods.lithium.common.shapes.VoxelShapeAlignedCuboid(
            Math.round(minX * 8.0) / 8.0,
            Math.round(minY * 8.0) / 8.0,
            Math.round(minZ * 8.0) / 8.0,
            Math.round(maxX * 8.0) / 8.0,
            Math.round(maxY * 8.0) / 8.0,
            Math.round(maxZ * 8.0) / 8.0,
            xBits,
            yBits,
            zBits
        );
    }

    public static VoxelShape create(final AABB aabb) {
        return create(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
    }

    @VisibleForTesting
    public static int findBits(final double min, final double max) { // MODIFIED for porting: lithium.accesswidener widened access
        if (!(min < -1.0E-7) && !(max > 1.0000001)) {
            for (int bits = 0; bits <= 3; bits++) {
                int intervals = 1 << bits;
                double shMin = min * intervals;
                double shMax = max * intervals;
                boolean foundMin = Math.abs(shMin - Math.round(shMin)) < 1.0E-7 * intervals;
                boolean foundMax = Math.abs(shMax - Math.round(shMax)) < 1.0E-7 * intervals;
                if (foundMin && foundMax) {
                    return bits;
                }
            }

            return -1;
        } else {
            return -1;
        }
    }

    @VisibleForTesting
    static long lcm(final int first, final int second) {
        return (long)first * (second / IntMath.gcd(first, second));
    }

    public static VoxelShape or(final VoxelShape first, final VoxelShape second) {
        return join(first, second, BooleanOp.OR);
    }

    public static VoxelShape or(final VoxelShape first, final VoxelShape... tail) {
        return Arrays.stream(tail).reduce(first, Shapes::or);
    }

    public static VoxelShape join(final VoxelShape first, final VoxelShape second, final BooleanOp op) {
        return joinUnoptimized(first, second, op).optimize();
    }

    public static VoxelShape joinUnoptimized(final VoxelShape first, final VoxelShape second, final BooleanOp op) {
        if (op.apply(false, false)) {
            throw (IllegalArgumentException)Util.pauseInIde(new IllegalArgumentException());
        }

        if (first == second) {
            return op.apply(true, true) ? first : empty();
        }

        boolean firstOnlyMatters = op.apply(true, false);
        boolean secondOnlyMatters = op.apply(false, true);
        if (first.isEmpty()) {
            return secondOnlyMatters ? second : empty();
        }

        if (second.isEmpty()) {
            return firstOnlyMatters ? first : empty();
        }

        IndexMerger xMerger = createIndexMerger(1, first.getCoords(Direction.Axis.X), second.getCoords(Direction.Axis.X), firstOnlyMatters, secondOnlyMatters);
        IndexMerger yMerger = createIndexMerger(
            xMerger.size() - 1, first.getCoords(Direction.Axis.Y), second.getCoords(Direction.Axis.Y), firstOnlyMatters, secondOnlyMatters
        );
        IndexMerger zMerger = createIndexMerger(
            (xMerger.size() - 1) * (yMerger.size() - 1),
            first.getCoords(Direction.Axis.Z),
            second.getCoords(Direction.Axis.Z),
            firstOnlyMatters,
            secondOnlyMatters
        );
        BitSetDiscreteVoxelShape voxelShape = BitSetDiscreteVoxelShape.join(first.shape, second.shape, xMerger, yMerger, zMerger, op);
        return xMerger instanceof DiscreteCubeMerger && yMerger instanceof DiscreteCubeMerger && zMerger instanceof DiscreteCubeMerger
            ? new CubeVoxelShape(voxelShape)
            : new ArrayVoxelShape(voxelShape, xMerger.getList(), yMerger.getList(), zMerger.getList());
    }

    public static boolean joinIsNotEmpty(final VoxelShape first, final VoxelShape second, final BooleanOp op) {
        if (op.apply(false, false)) {
            throw (IllegalArgumentException)Util.pauseInIde(new IllegalArgumentException());
        }

        boolean firstEmpty = first.isEmpty();
        boolean secondEmpty = second.isEmpty();
        if (!firstEmpty && !secondEmpty) {
            if (first == second) {
                return op.apply(true, true);
            }

            boolean firstOnlyMatters = op.apply(true, false);
            boolean secondOnlyMatters = op.apply(false, true);

            for (Direction.Axis axis : AxisCycle.AXIS_VALUES) {
                if (first.max(axis) < second.min(axis) - 1.0E-7) {
                    return firstOnlyMatters || secondOnlyMatters;
                }

                if (second.max(axis) < first.min(axis) - 1.0E-7) {
                    return firstOnlyMatters || secondOnlyMatters;
                }
            }

            // MODIFIED for porting: lithium shapes.optimized_matching ShapesMixin#cuboidMatchesAnywhere - when both
            // shapes are simple cuboids the answer can be computed directly, without building any index mergers.
            int lithium$matchesAnywhere = net.caffeinemc.mods.lithium.common.shapes.VoxelShapeMatchesAnywhere.cuboidMatchesAnywhere(first, second, op);
            if (lithium$matchesAnywhere != -1) {
                return lithium$matchesAnywhere != 0;
            }

            IndexMerger xMerger = createIndexMerger(
                1, first.getCoords(Direction.Axis.X), second.getCoords(Direction.Axis.X), firstOnlyMatters, secondOnlyMatters
            );
            IndexMerger yMerger = createIndexMerger(
                xMerger.size() - 1, first.getCoords(Direction.Axis.Y), second.getCoords(Direction.Axis.Y), firstOnlyMatters, secondOnlyMatters
            );
            IndexMerger zMerger = createIndexMerger(
                (xMerger.size() - 1) * (yMerger.size() - 1),
                first.getCoords(Direction.Axis.Z),
                second.getCoords(Direction.Axis.Z),
                firstOnlyMatters,
                secondOnlyMatters
            );
            return joinIsNotEmpty(xMerger, yMerger, zMerger, first.shape, second.shape, op);
        } else {
            return op.apply(!firstEmpty, !secondEmpty);
        }
    }

    private static boolean joinIsNotEmpty(
        final IndexMerger xMerger,
        final IndexMerger yMerger,
        final IndexMerger zMerger,
        final DiscreteVoxelShape first,
        final DiscreteVoxelShape second,
        final BooleanOp op
    ) {
        return !xMerger.forMergedIndexes(
            (x1, x2, xr) -> yMerger.forMergedIndexes(
                (y1, y2, yr) -> zMerger.forMergedIndexes((z1, z2, zr) -> !op.apply(first.isFullWide(x1, y1, z1), second.isFullWide(x2, y2, z2)))
            )
        );
    }

    public static double collide(final Direction.Axis axis, final AABB moving, final Iterable<VoxelShape> shapes, double distance) {
        // MODIFIED for porting: was VFP movement.collision MixinShapes#calculateMaxOffset1_12_2 (@Inject HEAD cancellable)
        // Targets <=1.12.2 swept every AABB of every shape with the pre-1.13 per-box clamp and had no 1.0E-7 early-out.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            for (final VoxelShape shape : shapes) {
                for (final AABB shapeBox : shape.toAabbs()) {
                    distance = switch (axis) {
                        case X -> vfpIntersectX(moving, shapeBox, distance);
                        case Y -> vfpIntersectY(moving, shapeBox, distance);
                        case Z -> vfpIntersectZ(moving, shapeBox, distance);
                    };
                }
            }

            return distance;
        }

        for (VoxelShape shape : shapes) {
            if (Math.abs(distance) < 1.0E-7) {
                return 0.0;
            }

            distance = shape.collide(axis, moving, distance);
        }

        return distance;
    }

    // MODIFIED for porting: was VFP movement.collision MixinShapes#viaFabricPlus$intersectX (@Unique helper)
    private static double vfpIntersectX(final AABB box, final AABB shapeBox, double maxDist) {
        if (box.maxY <= shapeBox.minY || box.minY >= shapeBox.maxY || box.maxZ <= shapeBox.minZ || box.minZ >= shapeBox.maxZ) {
            return maxDist;
        }

        double e;
        if (maxDist > 0.0 && box.maxX <= shapeBox.minX) {
            double d = shapeBox.minX - box.maxX;
            if (d < maxDist) {
                maxDist = d;
            }
        } else if (maxDist < 0.0 && box.minX >= shapeBox.maxX && (e = shapeBox.maxX - box.minX) > maxDist) {
            maxDist = e;
        }

        return maxDist;
    }

    // MODIFIED for porting: was VFP movement.collision MixinShapes#viaFabricPlus$intersectY (@Unique helper)
    private static double vfpIntersectY(final AABB playerBox, final AABB shapeBox, double maxDist) {
        if (playerBox.maxX <= shapeBox.minX || playerBox.minX >= shapeBox.maxX || playerBox.maxZ <= shapeBox.minZ || playerBox.minZ >= shapeBox.maxZ) {
            return maxDist;
        }

        double e;
        if (maxDist > 0.0 && playerBox.maxY <= shapeBox.minY) {
            double d = shapeBox.minY - playerBox.maxY;
            if (d < maxDist) {
                maxDist = d;
            }
        } else if (maxDist < 0.0 && playerBox.minY >= shapeBox.maxY && (e = shapeBox.maxY - playerBox.minY) > maxDist) {
            maxDist = e;
        }

        return maxDist;
    }

    // MODIFIED for porting: was VFP movement.collision MixinShapes#viaFabricPlus$intersectZ (@Unique helper)
    private static double vfpIntersectZ(final AABB playerBox, final AABB shapeBox, double maxDist) {
        if (playerBox.maxX <= shapeBox.minX || playerBox.minX >= shapeBox.maxX || playerBox.maxY <= shapeBox.minY || playerBox.minY >= shapeBox.maxY) {
            return maxDist;
        }

        double e;
        if (maxDist > 0.0 && playerBox.maxZ <= shapeBox.minZ) {
            double d = shapeBox.minZ - playerBox.maxZ;
            if (d < maxDist) {
                maxDist = d;
            }
        } else if (maxDist < 0.0 && playerBox.minZ >= shapeBox.maxZ && (e = shapeBox.maxZ - playerBox.minZ) > maxDist) {
            maxDist = e;
        }

        return maxDist;
    }

    public static boolean blockOccludes(final VoxelShape shape, final VoxelShape occluder, final Direction direction) {
        if (shape == block() && occluder == block()) {
            return true;
        }

        if (occluder.isEmpty()) {
            return false;
        }

        Direction.Axis axis = direction.getAxis();
        Direction.AxisDirection sign = direction.getAxisDirection();
        VoxelShape first = sign == Direction.AxisDirection.POSITIVE ? shape : occluder;
        VoxelShape second = sign == Direction.AxisDirection.POSITIVE ? occluder : shape;
        BooleanOp op = sign == Direction.AxisDirection.POSITIVE ? BooleanOp.ONLY_FIRST : BooleanOp.ONLY_SECOND;
        return DoubleMath.fuzzyEquals(first.max(axis), 1.0, 1.0E-7)
            && DoubleMath.fuzzyEquals(second.min(axis), 0.0, 1.0E-7)
            && !joinIsNotEmpty(new SliceShape(first, axis, first.shape.getSize(axis) - 1), new SliceShape(second, axis, 0), op);
    }

    public static boolean mergedFaceOccludes(final VoxelShape shape, final VoxelShape occluder, final Direction direction) {
        if (shape != block() && occluder != block()) {
            Direction.Axis axis = direction.getAxis();
            Direction.AxisDirection sign = direction.getAxisDirection();
            VoxelShape first = sign == Direction.AxisDirection.POSITIVE ? shape : occluder;
            VoxelShape second = sign == Direction.AxisDirection.POSITIVE ? occluder : shape;
            if (!DoubleMath.fuzzyEquals(first.max(axis), 1.0, 1.0E-7)) {
                first = empty();
            }

            if (!DoubleMath.fuzzyEquals(second.min(axis), 0.0, 1.0E-7)) {
                second = empty();
            }

            return !joinIsNotEmpty(
                block(),
                joinUnoptimized(new SliceShape(first, axis, first.shape.getSize(axis) - 1), new SliceShape(second, axis, 0), BooleanOp.OR),
                BooleanOp.ONLY_FIRST
            );
        } else {
            return true;
        }
    }

    public static boolean faceShapeOccludes(final VoxelShape shape, final VoxelShape occluder) {
        if (shape == block() || occluder == block()) {
            return true;
        } else {
            return shape.isEmpty() && occluder.isEmpty()
                ? false
                : !joinIsNotEmpty(block(), joinUnoptimized(shape, occluder, BooleanOp.OR), BooleanOp.ONLY_FIRST);
        }
    }

    @VisibleForTesting
    static IndexMerger createIndexMerger(
        final int cost, final DoubleList first, final DoubleList second, final boolean firstOnlyMatters, final boolean secondOnlyMatters
    ) {
        int firstSize = first.size() - 1;
        int secondSize = second.size() - 1;
        if (first instanceof CubePointRange && second instanceof CubePointRange) {
            long size = lcm(firstSize, secondSize);
            if (cost * size <= 256L) {
                return new DiscreteCubeMerger(firstSize, secondSize);
            }
        }

        if (first.getDouble(firstSize) < second.getDouble(0) - 1.0E-7) {
            return new NonOverlappingMerger(first, second, false);
        } else if (second.getDouble(secondSize) < first.getDouble(0) - 1.0E-7) {
            return new NonOverlappingMerger(second, first, true);
        } else {
            // MODIFIED for porting: lithium shapes.shape_merging ShapesMixin#injectCustomListPair replaces
            // IndirectMerger by lithium's own pair list implementation.
            return firstSize == secondSize && Objects.equals(first, second)
                ? new IdenticalMerger(first)
                : new net.caffeinemc.mods.lithium.common.shapes.pairs.LithiumDoublePairList(first, second, firstOnlyMatters, secondOnlyMatters);
        }
    }

    public static VoxelShape rotate(final VoxelShape shape, final OctahedralGroup rotation) {
        return rotate(shape, rotation, BLOCK_CENTER);
    }

    public static VoxelShape rotate(final VoxelShape shape, final OctahedralGroup rotation, final Vec3 rotationPoint) {
        if (rotation == OctahedralGroup.IDENTITY) {
            return shape;
        }

        DiscreteVoxelShape newDiscreteShape = shape.shape.rotate(rotation);
        if (shape instanceof CubeVoxelShape && BLOCK_CENTER.equals(rotationPoint)) {
            return new CubeVoxelShape(newDiscreteShape);
        }

        Direction.Axis newX = rotation.permutation().permuteAxis(Direction.Axis.X);
        Direction.Axis newY = rotation.permutation().permuteAxis(Direction.Axis.Y);
        Direction.Axis newZ = rotation.permutation().permuteAxis(Direction.Axis.Z);
        DoubleList newXs = shape.getCoords(newX);
        DoubleList newYs = shape.getCoords(newY);
        DoubleList newZs = shape.getCoords(newZ);
        boolean flipX = rotation.inverts(Direction.Axis.X);
        boolean flipY = rotation.inverts(Direction.Axis.Y);
        boolean flipZ = rotation.inverts(Direction.Axis.Z);
        return new ArrayVoxelShape(
            newDiscreteShape,
            flipAxisIfNeeded(newXs, flipX, rotationPoint.get(newX), rotationPoint.x),
            flipAxisIfNeeded(newYs, flipY, rotationPoint.get(newY), rotationPoint.y),
            flipAxisIfNeeded(newZs, flipZ, rotationPoint.get(newZ), rotationPoint.z)
        );
    }

    @VisibleForTesting
    static DoubleList flipAxisIfNeeded(final DoubleList newAxis, final boolean flip, final double newRelative, final double oldRelative) {
        if (!flip && newRelative == oldRelative) {
            return newAxis;
        }

        int size = newAxis.size();
        DoubleList newList = new DoubleArrayList(size);
        if (flip) {
            for (int i = size - 1; i >= 0; i--) {
                newList.add(-(newAxis.getDouble(i) - newRelative) + oldRelative);
            }
        } else {
            for (int i = 0; i >= 0 && i < size; i++) {
                newList.add(newAxis.getDouble(i) - newRelative + oldRelative);
            }
        }

        return newList;
    }

    public static boolean equal(final VoxelShape first, final VoxelShape second) {
        return !joinIsNotEmpty(first, second, BooleanOp.NOT_SAME);
    }

    public static Map<Direction.Axis, VoxelShape> rotateHorizontalAxis(final VoxelShape zAxis) {
        return rotateHorizontalAxis(zAxis, BLOCK_CENTER);
    }

    public static Map<Direction.Axis, VoxelShape> rotateHorizontalAxis(final VoxelShape zAxis, final Vec3 rotationCenter) {
        return Maps.newEnumMap(Map.of(Direction.Axis.Z, zAxis, Direction.Axis.X, rotate(zAxis, OctahedralGroup.BLOCK_ROT_Y_90, rotationCenter)));
    }

    public static Map<Direction.Axis, VoxelShape> rotateAllAxis(final VoxelShape north) {
        return rotateAllAxis(north, BLOCK_CENTER);
    }

    public static Map<Direction.Axis, VoxelShape> rotateAllAxis(final VoxelShape north, final Vec3 rotationCenter) {
        return Maps.newEnumMap(
            Map.of(
                Direction.Axis.Z,
                north,
                Direction.Axis.X,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_90, rotationCenter),
                Direction.Axis.Y,
                rotate(north, OctahedralGroup.BLOCK_ROT_X_90, rotationCenter)
            )
        );
    }

    public static Map<Direction, VoxelShape> rotateHorizontal(final VoxelShape north) {
        return rotateHorizontal(north, OctahedralGroup.IDENTITY, BLOCK_CENTER);
    }

    public static Map<Direction, VoxelShape> rotateHorizontal(final VoxelShape north, final OctahedralGroup initial) {
        return rotateHorizontal(north, initial, BLOCK_CENTER);
    }

    public static Map<Direction, VoxelShape> rotateHorizontal(final VoxelShape north, final OctahedralGroup initial, final Vec3 rotationCenter) {
        return Maps.newEnumMap(
            Map.of(
                Direction.NORTH,
                rotate(north, initial),
                Direction.EAST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_90.compose(initial), rotationCenter),
                Direction.SOUTH,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_180.compose(initial), rotationCenter),
                Direction.WEST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_270.compose(initial), rotationCenter)
            )
        );
    }

    public static Map<Direction, VoxelShape> rotateAll(final VoxelShape north) {
        return rotateAll(north, OctahedralGroup.IDENTITY, BLOCK_CENTER);
    }

    public static Map<Direction, VoxelShape> rotateAll(final VoxelShape north, final Vec3 rotationCenter) {
        return rotateAll(north, OctahedralGroup.IDENTITY, rotationCenter);
    }

    public static Map<Direction, VoxelShape> rotateAll(final VoxelShape north, final OctahedralGroup initial, final Vec3 rotationCenter) {
        return Maps.newEnumMap(
            Map.of(
                Direction.NORTH,
                rotate(north, initial),
                Direction.EAST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_90.compose(initial), rotationCenter),
                Direction.SOUTH,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_180.compose(initial), rotationCenter),
                Direction.WEST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_270.compose(initial), rotationCenter),
                Direction.UP,
                rotate(north, OctahedralGroup.BLOCK_ROT_X_270.compose(initial), rotationCenter),
                Direction.DOWN,
                rotate(north, OctahedralGroup.BLOCK_ROT_X_90.compose(initial), rotationCenter)
            )
        );
    }

    public static Map<AttachFace, Map<Direction, VoxelShape>> rotateAttachFace(final VoxelShape north) {
        return rotateAttachFace(north, OctahedralGroup.IDENTITY);
    }

    public static Map<AttachFace, Map<Direction, VoxelShape>> rotateAttachFace(final VoxelShape north, final OctahedralGroup initial) {
        return Map.of(
            AttachFace.WALL,
            rotateHorizontal(north, initial),
            AttachFace.FLOOR,
            rotateHorizontal(north, OctahedralGroup.BLOCK_ROT_X_270.compose(initial)),
            AttachFace.CEILING,
            rotateHorizontal(north, OctahedralGroup.BLOCK_ROT_Y_180.compose(OctahedralGroup.BLOCK_ROT_X_90).compose(initial))
        );
    }

    public interface DoubleLineConsumer {
        void consume(double x1, double y1, double z1, double x2, double y2, double z2);
    }
}