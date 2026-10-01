package com.mentalfrostbyte.jello.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;

/**
 * How the maps colour the ground: each column of a chunk by the block at the top of it - its map colour, white under snow,
 * the lava colour under lava, water's for anything waterlogged, and a shade lighter or darker where the column next to it
 * to the north or south is open air, which is what gives the ground its relief. The minimap and the Maps page both colour
 * chunks this way, so what one shows is what the other saved.
 */
public final class ChunkColours {
    /** A column with no colour of its own (air all the way down). */
    public static final int NONE = 0xFF8A8A8A;

    private ChunkColours() {}

    /** The 256 columns of {@code chunk}, row by row from its north-west corner, as opaque ARGB. */
    public static int[] of(final ClientLevel world, final LevelChunk chunk) {
        int[] colours = new int[256];
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // getHeight is already the y of the top block.
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                colours[z * 16 + x] = column(world, pos.set(baseX + x, top, baseZ + z));
            }
        }

        return colours;
    }

    /** The colour of the column whose top block is at {@code pos}. */
    public static int column(final ClientLevel world, final BlockPos.MutableBlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            pos.move(0, -1, 0);
            state = world.getBlockState(pos);
        }

        int rgb = state.getMapColor(world, pos).col;
        BlockState above = world.getBlockState(pos.above());
        if (above.is(Blocks.SNOW) || above.is(Blocks.SNOW_BLOCK) || above.is(Blocks.POWDER_SNOW)) {
            rgb = 0xFFFFFF;
        } else if (above.getFluidState().is(Fluids.LAVA) || above.getFluidState().is(Fluids.FLOWING_LAVA)) {
            rgb = MapColor.FIRE.col;
        }
        if (!state.getFluidState().isEmpty() && state.getFluidState().is(FluidTags.WATER)) {
            rgb = MapColor.WATER.col;
        }

        BlockState north = world.getBlockState(pos.north());
        BlockState south = world.getBlockState(pos.south());
        if (north.isAir() || north.is(Blocks.SNOW)) {
            rgb = blend(rgb, 0x000000, 0.6F);
        } else if (south.isAir() || south.is(Blocks.SNOW)) {
            rgb = blend(rgb, 0xFFFFFF, 0.6F);
        }

        return rgb == 0 ? NONE : 0xFF000000 | rgb;
    }

    /** {@code from} moved {@code amount} of the way to {@code to}. */
    public static int blend(final int from, final int to, final float amount) {
        int r = Math.round((from >> 16 & 0xFF) * (1.0F - amount) + (to >> 16 & 0xFF) * amount);
        int g = Math.round((from >> 8 & 0xFF) * (1.0F - amount) + (to >> 8 & 0xFF) * amount);
        int b = Math.round((from & 0xFF) * (1.0F - amount) + (to & 0xFF) * amount);
        return r << 16 | g << 8 | b;
    }
}
