package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.module.impl.movement.blockfly.BlockFlyPlacement.Target;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockFlyPlacementTest {

    private static CollisionContext CONTEXT;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CONTEXT = CollisionContext.empty();
    }

    @Test
    void choosesTheTopBeforeTheSidesAndUsesContinuousYaw() {
        TinyWorld world = new TinyWorld();
        world.set(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        world.set(new BlockPos(1, 1, 0), Blocks.STONE.defaultBlockState());
        Vec3 eyes = new Vec3(0.5, 3.0, -1.0);
        Target target = BlockFlyPlacement.find(world, CONTEXT, eyes, new BlockPos(0, 1, 0), 4.5, new Rotation(720, 60));

        assertNotNull(target);
        assertEquals(new BlockPos(0, 0, 0), target.hit().getBlockPos());
        assertEquals(Direction.UP, target.hit().getDirection());
        assertTrue(target.rotation().yaw() >= 540 && target.rotation().yaw() <= 900);
        assertTrue(BlockFlyPlacement.valid(world, CONTEXT, eyes, target, target.rotation(), 4.5));
    }

    @Test
    void usesTheActualHalfSlabOutline() {
        TinyWorld world = new TinyWorld();
        world.set(BlockPos.ZERO, Blocks.STONE_SLAB.defaultBlockState());
        Vec3 eyes = new Vec3(0.5, 2.6, 0.5);
        Target target = BlockFlyPlacement.find(world, CONTEXT, eyes, BlockPos.ZERO.above(), 4.5, null);

        assertNotNull(target);
        assertEquals(Direction.UP, target.hit().getDirection());
        assertEquals(0.5, target.hit().getLocation().y, 1.0E-6);
    }

    @Test
    void rejectsUnsupportedOccupiedOccludedAndOutOfReachTargets() {
        TinyWorld world = new TinyWorld();
        Vec3 eyes = new Vec3(0.5, 3, 0.5);
        BlockPos placed = BlockPos.ZERO.above();
        assertNull(BlockFlyPlacement.find(world, CONTEXT, eyes, placed, 4.5, null));
        world.set(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        assertNull(BlockFlyPlacement.find(world, CONTEXT, eyes, placed, 1.5, null));
        world.set(placed, Blocks.STONE.defaultBlockState());
        assertNull(BlockFlyPlacement.find(world, CONTEXT, eyes, placed, 4.5, null));

        TinyWorld occluded = new TinyWorld();
        occluded.set(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        Vec3 sideEyes = new Vec3(3, 0.75, 0.5);
        Rotation look = Rotations.towards(sideEyes, new Vec3(1, 0.75, 0.5));
        Target target = new Target(new BlockPos(1, 0, 0),
                new BlockHitResult(new Vec3(1, 0.75, 0.5), Direction.EAST, BlockPos.ZERO, false), look, sideEyes);
        assertTrue(BlockFlyPlacement.valid(occluded, CONTEXT, sideEyes, target, look, 4.5));
        occluded.set(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState());
        assertFalse(BlockFlyPlacement.valid(occluded, CONTEXT, sideEyes, target, look, 4.5));
    }

    @Test
    void rejectsAnIncorrectFaceAndAPhantomAnchor() {
        TinyWorld world = new TinyWorld();
        world.set(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        Vec3 eyes = new Vec3(0.5, 2.6, 0.5);
        Rotation down = new Rotation(0, 90);
        Target wrongFace = new Target(new BlockPos(1, 0, 0),
                new BlockHitResult(new Vec3(1, 0.5, 0.5), Direction.EAST, BlockPos.ZERO, false), down, eyes);
        assertFalse(BlockFlyPlacement.valid(world, CONTEXT, eyes, wrongFace, down, 4.5));
        Target wrongNeighbour = new Target(new BlockPos(2, 0, 0),
                new BlockHitResult(new Vec3(0.5, 1, 0.5), Direction.UP, BlockPos.ZERO, false), down, eyes);
        assertFalse(BlockFlyPlacement.valid(world, CONTEXT, eyes, wrongNeighbour, down, 4.5));
    }

    @Test
    void nearbySearchIsBoundedAndOrdersNearestPositionsFirst() {
        List<BlockPos> positions = BlockFlyPlacement.positionsAround(new BlockPos(-5, 30, -7), 100);
        assertEquals(new BlockPos(-5, 30, -7), positions.getFirst());
        assertEquals(new BlockPos(-5, 29, -7), positions.get(1));
        assertEquals(63, positions.size());
        assertEquals(positions, BlockFlyPlacement.positionsAround(new BlockPos(-5, 30, -7), 3));
        assertEquals(List.of(BlockPos.ZERO), BlockFlyPlacement.positionsAround(BlockPos.ZERO, -1));
    }

    @Test
    void samplePointsStayOnTheRealFaceAtNegativeCoordinates() {
        AABB slab = new AABB(0, 0, 0, 1, 0.5, 1);
        assertEquals(new Vec3(-1.5, -2.5, -3.5),
                BlockFlyPlacement.facePoint(slab, new BlockPos(-2, -3, -4), Direction.UP, 0.5, 0.5));
        assertEquals(new Vec3(-2, -2.75, -3.5),
                BlockFlyPlacement.facePoint(slab, new BlockPos(-2, -3, -4), Direction.WEST, 0.5, 0.5));
    }

    @Test
    void inventoryFilterKeepsBridgeBlocksAndSkipsDecorationsAndFallingBlocks() {
        assertTrue(BlockFlyPlacement.isPlaceable(Blocks.STONE));
        assertTrue(BlockFlyPlacement.isPlaceable(Blocks.GLASS));
        assertTrue(BlockFlyPlacement.isPlaceable(Blocks.STONE_SLAB));
        assertFalse(BlockFlyPlacement.isPlaceable(ItemStack.EMPTY));
        assertFalse(BlockFlyPlacement.isPlaceable(Blocks.TORCH));
        assertFalse(BlockFlyPlacement.isPlaceable(Blocks.SAND));
        assertFalse(BlockFlyPlacement.isPlaceable(Blocks.CHEST));
        assertFalse(BlockFlyPlacement.isPlaceable(Blocks.DIAMOND_ORE));
    }

    private static final class TinyWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(final BlockPos pos, final BlockState state) {
            this.blocks.put(pos.immutable(), state);
        }

        @Override
        public BlockEntity getBlockEntity(final BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(final BlockPos pos) {
            return this.blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(final BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
