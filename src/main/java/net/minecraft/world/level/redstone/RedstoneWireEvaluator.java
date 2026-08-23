package net.minecraft.world.level.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public abstract class RedstoneWireEvaluator {
    protected final RedStoneWireBlock wireBlock;

    protected RedstoneWireEvaluator(final RedStoneWireBlock wireBlock) {
        this.wireBlock = wireBlock;
    }

    public abstract void updatePowerStrength(
        final Level level, final BlockPos pos, final BlockState state, final @Nullable Orientation orientation, final boolean skipShapeUpdates
    );

    protected int getBlockSignal(final Level level, final BlockPos pos) {
        return this.wireBlock.getBlockSignal(level, pos);
    }

    public int getWireSignal(final BlockPos pos, final BlockState state) { // MODIFIED for porting: lithium.accesswidener widened access
        return state.is(this.wireBlock) ? state.getValue(RedStoneWireBlock.POWER) : 0;
    }

    protected int getIncomingWireSignal(final Level level, final BlockPos pos) {
        // MODIFIED for porting: lithium block.redstone_wire RedstoneWireEvaluatorMixin#getIncomingWireSignalFaster
        // (HEAD, cancellable, so the vanilla body below was unreachable) - avoids reading the same neighbouring blocks
        // several times.
        return net.caffeinemc.mods.lithium.common.block.redstone.RedstoneWirePowerCalculations.getNeighborWireSignal(this.wireBlock, this, level, pos);
    }
}