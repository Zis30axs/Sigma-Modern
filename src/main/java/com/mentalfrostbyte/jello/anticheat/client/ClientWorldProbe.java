package com.mentalfrostbyte.jello.anticheat.client;

import com.mentalfrostbyte.jello.anticheat.observe.WorldProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@link WorldProbe} over the level the client is in. Read from the game thread only.
 *
 * <p>Support is decided the way Grim's NoFall does it - a feet box a thousandth of a block tall, widened by
 * the slack the caller asks for, tested against block collision shapes and against entities players can stand
 * on - with the block and entity lookups delegated to the level's own collision code.</p>
 */
public final class ClientWorldProbe implements WorldProbe {

    /** A player's hitbox is 0.6 wide. */
    private static final double HALF_WIDTH = 0.3;
    private static final double FEET_HEIGHT = 0.001;
    /** vanilla looks for the block that affects movement half a block under the feet. */
    private static final double FRICTION_PROBE_DEPTH = 0.500001;

    private ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    @Override
    public boolean chunkLoaded(final double x, final double z) {
        ClientLevel level = this.level();
        return level != null && level.hasChunkAt(BlockPos.containing(x, level.getMinY(), z));
    }

    @Override
    public double blockFriction(final double x, final double y, final double z) {
        ClientLevel level = this.level();
        if (level == null) {
            return 0.6;
        }

        return level.getBlockState(BlockPos.containing(x, y - FRICTION_PROBE_DEPTH, z)).getBlock().getFriction();
    }

    @Override
    public boolean supported(final double x, final double y, final double z, final double horizontalSlack,
                             final double verticalSlack) {
        ClientLevel level = this.level();
        if (level == null) {
            return true;
        }

        double reach = HALF_WIDTH + horizontalSlack;
        AABB feet = new AABB(x - reach, y - verticalSlack, z - reach, x + reach, y + FEET_HEIGHT, z + reach);
        for (VoxelShape shape : level.getBlockCollisions(null, feet)) {
            if (!shape.isEmpty()) {
                return true;
            }
        }

        // Boats, shulkers and minecarts hold a player up like a block does; a generous box, as Grim's is.
        return !level.getEntities((Entity) null, feet.inflate(0.5, 0.5, 0.5), entity -> entity.canBeCollidedWith(null)).isEmpty();
    }

    @Override
    public boolean unjudgedEnvironment(final double x, final double y, final double z) {
        ClientLevel level = this.level();
        if (level == null) {
            return true;
        }

        int minX = (int) Math.floor(x - 0.4);
        int maxX = (int) Math.floor(x + 0.4);
        int minZ = (int) Math.floor(z - 0.4);
        int maxZ = (int) Math.floor(z + 0.4);
        int minY = (int) Math.floor(y - 1.0);
        int maxY = (int) Math.floor(y + 1.9);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (changesMovement(level.getBlockState(pos.set(bx, by, bz)))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /** Blocks whose effect on a player v1 does not model: fluids, climbables, and the sticky or bouncy ones. */
    private static boolean changesMovement(final BlockState state) {
        return !state.getFluidState().isEmpty()
                || state.is(BlockTags.CLIMBABLE)
                || state.is(BlockTags.BEDS)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.HONEY_BLOCK)
                || state.is(Blocks.SLIME_BLOCK)
                || state.is(Blocks.BUBBLE_COLUMN)
                || state.is(Blocks.SCAFFOLDING)
                || state.is(Blocks.MOVING_PISTON);
    }
}
