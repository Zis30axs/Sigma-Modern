package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Shared scaffold geometry. Every selected anchor is checked against the same outline ray used by vanilla picking. */
public final class BlockFlyPlacement {

    private static final Direction[] FACES = {
            Direction.UP, Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.DOWN
    };
    private static final double[] SAMPLE = {0.5, 0.15, 0.85};

    // The original BlockFly's inventory filter; falling blocks are excluded as a family in modern versions.
    private static final Set<Block> EXCLUDED = Set.of(
            Blocks.AIR, Blocks.WATER, Blocks.LAVA, Blocks.ENCHANTING_TABLE, Blocks.CARPET.black(),
            Blocks.GLASS_PANE, Blocks.IRON_BARS, Blocks.ICE, Blocks.PACKED_ICE, Blocks.CHEST,
            Blocks.TRAPPED_CHEST, Blocks.TORCH, Blocks.ANVIL, Blocks.NOTE_BLOCK, Blocks.JUKEBOX,
            Blocks.TNT, Blocks.REDSTONE_WIRE, Blocks.LEVER, Blocks.COBBLESTONE_WALL, Blocks.OAK_FENCE,
            Blocks.TALL_GRASS, Blocks.TRIPWIRE, Blocks.TRIPWIRE_HOOK, Blocks.RAIL, Blocks.LILY_PAD,
            Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM, Blocks.VINE, Blocks.ACACIA_TRAPDOOR,
            Blocks.LADDER, Blocks.FURNACE, Blocks.SAND, Blocks.CACTUS, Blocks.DISPENSER,
            Blocks.DROPPER, Blocks.CRAFTING_TABLE, Blocks.COBWEB, Blocks.PUMPKIN, Blocks.ACACIA_SAPLING
    );

    public record Target(BlockPos placedPos, BlockHitResult hit, Rotation rotation, Vec3 eyes) {
        public Target {
            placedPos = placedPos.immutable();
        }
    }

    private BlockFlyPlacement() {
    }

    public static boolean isPlaceable(final ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return false;
        }
        return isPlaceable(item.getBlock());
    }

    static boolean isPlaceable(final Block block) {
        return !EXCLUDED.contains(block)
                && !(block instanceof ButtonBlock)
                && !(block instanceof BushBlock)
                && !(block instanceof TrapDoorBlock)
                && !(block instanceof BasePressurePlateBlock)
                && !(block instanceof FallingBlock)
                && !(block instanceof DropExperienceBlock)
                && !(block instanceof AbstractSkullBlock)
                && !(block instanceof BedBlock)
                && !(block instanceof AbstractBannerBlock)
                && !(block instanceof AbstractChestBlock<?>)
                && !(block instanceof DoorBlock);
    }

    public static @Nullable Target find(final LocalPlayer player, final Vec3 eyes, final BlockPos placedPos,
                                         final @Nullable Rotation previous) {
        if (!inBounds(player, placedPos)) {
            return null;
        }
        return find(player.level(), CollisionContext.of(player), eyes, placedPos, player.blockInteractionRange(), previous);
    }

    public static @Nullable Target findAround(final LocalPlayer player, final Vec3 eyes, final BlockPos base,
                                               final int radius, final @Nullable Rotation previous) {
        for (BlockPos candidate : positionsAround(base, radius)) {
            Target target = find(player, eyes, candidate, previous);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    public static @Nullable BlockHitResult raycast(final LocalPlayer player, final Vec3 eyes, final Rotation rotation) {
        return raycast(player.level(), CollisionContext.of(player), eyes, rotation, player.blockInteractionRange());
    }

    public static @Nullable Target fromHit(final LocalPlayer player, final Vec3 eyes, final @Nullable BlockHitResult hit,
                                            final Rotation rotation) {
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
            return null;
        }
        BlockPos placedPos = hit.getBlockPos().relative(hit.getDirection());
        Target target = new Target(placedPos, hit, rotation, eyes);
        return valid(player, eyes, target, rotation) ? target : null;
    }

    public static boolean valid(final LocalPlayer player, final Vec3 eyes, final Target target, final Rotation rotation) {
        return inBounds(player, target.placedPos())
                && valid(player.level(), CollisionContext.of(player), eyes, target, rotation, player.blockInteractionRange());
    }

    private static boolean inBounds(final LocalPlayer player, final BlockPos pos) {
        return player.level().isInWorldBounds(pos) && player.level().getWorldBorder().isWithinBounds(pos);
    }

    // The small BlockGetter boundary keeps ray selection independently testable without creating a client or network.
    static @Nullable Target find(final BlockGetter level, final CollisionContext context, final Vec3 eyes,
                                  final BlockPos placedPos, final double range, final @Nullable Rotation previous) {
        if (level.isOutsideBuildHeight(placedPos) || !level.getBlockState(placedPos).canBeReplaced()) {
            return null;
        }
        for (Direction face : FACES) {
            BlockPos anchor = placedPos.relative(face.getOpposite());
            BlockState state = level.getBlockState(anchor);
            if (state.canBeReplaced()) {
                continue;
            }
            VoxelShape shape = state.getShape(level, anchor, context);
            Target best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (AABB box : shape.toAabbs()) {
                for (double first : SAMPLE) {
                    for (double second : SAMPLE) {
                        Vec3 point = facePoint(box, anchor, face, first, second);
                        if (eyes.distanceToSqr(point) > range * range) {
                            continue;
                        }
                        Rotation look = Rotations.towards(eyes, point);
                        if (previous != null) {
                            look = Rotations.continuous(previous, look);
                        }
                        BlockHitResult hit = raycast(level, context, eyes, look, range);
                        if (!matches(hit, anchor, face)) {
                            continue;
                        }
                        double score = previous == null ? eyes.distanceToSqr(hit.getLocation())
                                : square(look.yaw() - previous.yaw()) + square(look.pitch() - previous.pitch());
                        if (score < bestScore) {
                            bestScore = score;
                            best = new Target(placedPos, hit, look, eyes);
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    static @Nullable BlockHitResult raycast(final BlockGetter level, final CollisionContext context, final Vec3 eyes,
                                            final Rotation rotation, final double range) {
        if (!Double.isFinite(range) || range <= 0 || !Float.isFinite(rotation.yaw()) || !Float.isFinite(rotation.pitch())) {
            return null;
        }
        Vec3 end = eyes.add(Vec3.directionFromRotation(rotation.pitch(), rotation.yaw()).scale(range));
        BlockHitResult hit = level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, context));
        return hit.getType() == HitResult.Type.BLOCK && !hit.isInside() ? hit : null;
    }

    static boolean valid(final BlockGetter level, final CollisionContext context, final Vec3 eyes,
                         final Target target, final Rotation rotation, final double range) {
        BlockHitResult intended = target.hit();
        if (!intended.getBlockPos().relative(intended.getDirection()).equals(target.placedPos())
                || level.isOutsideBuildHeight(target.placedPos())
                || !level.getBlockState(target.placedPos()).canBeReplaced()
                || level.getBlockState(intended.getBlockPos()).canBeReplaced()) {
            return false;
        }
        return matches(raycast(level, context, eyes, rotation, range), intended.getBlockPos(), intended.getDirection());
    }

    static List<BlockPos> positionsAround(final BlockPos base, final int radius) {
        int bounded = Math.clamp(radius, 0, 3);
        List<BlockPos> positions = new ArrayList<>();
        for (int x = -bounded; x <= bounded; x++) {
            for (int y = -bounded; y <= bounded; y++) {
                for (int z = -bounded; z <= bounded; z++) {
                    if (Math.abs(x) + Math.abs(y) + Math.abs(z) <= bounded) {
                        positions.add(base.offset(x, y, z));
                    }
                }
            }
        }
        positions.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(base))
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return positions;
    }

    static Vec3 facePoint(final AABB box, final BlockPos pos, final Direction face, final double first, final double second) {
        double x = box.minX + box.getXsize() * first;
        double y = box.minY + box.getYsize() * second;
        double z = box.minZ + box.getZsize() * second;
        return switch (face) {
            case UP -> new Vec3(x + pos.getX(), box.maxY + pos.getY(), z + pos.getZ());
            case DOWN -> new Vec3(x + pos.getX(), box.minY + pos.getY(), z + pos.getZ());
            case NORTH -> new Vec3(x + pos.getX(), y + pos.getY(), box.minZ + pos.getZ());
            case SOUTH -> new Vec3(x + pos.getX(), y + pos.getY(), box.maxZ + pos.getZ());
            case WEST -> new Vec3(box.minX + pos.getX(), y + pos.getY(), box.minZ + box.getZsize() * first + pos.getZ());
            case EAST -> new Vec3(box.maxX + pos.getX(), y + pos.getY(), box.minZ + box.getZsize() * first + pos.getZ());
        };
    }

    private static boolean matches(final @Nullable BlockHitResult hit, final BlockPos pos, final Direction face) {
        return hit != null && !hit.isInside() && hit.getBlockPos().equals(pos) && hit.getDirection() == face;
    }

    private static double square(final double value) {
        return value * value;
    }
}
