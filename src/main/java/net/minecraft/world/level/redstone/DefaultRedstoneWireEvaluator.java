package net.minecraft.world.level.redstone;

import com.google.common.collect.Sets;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public class DefaultRedstoneWireEvaluator extends RedstoneWireEvaluator {
    public DefaultRedstoneWireEvaluator(final RedStoneWireBlock wireBlock) {
        super(wireBlock);
    }

    @Override
    public void updatePowerStrength(
        final Level level, final BlockPos pos, final BlockState state, final @Nullable Orientation orientation, final boolean skipShapeUpdates
    ) {
        int targetStrength = this.calculateTargetStrength(level, pos);
        if (state.getValue(RedStoneWireBlock.POWER) != targetStrength) {
            if (level.getBlockState(pos) == state) {
                level.setBlock(pos, state.setValue(RedStoneWireBlock.POWER, targetStrength), 2);
            }

            Set<BlockPos> toUpdate = Sets.newHashSet();
            toUpdate.add(pos);

            for (Direction direction : Direction.values()) {
                toUpdate.add(pos.relative(direction));
            }

            for (BlockPos blockPos : toUpdate) {
                level.updateNeighborsAt(blockPos, this.wireBlock);
            }
        }
    }

    private int calculateTargetStrength(final Level level, final BlockPos pos) {
        // MODIFIED for porting: lithium block.redstone_wire DefaultRedstoneWireEvaluatorMixin#calculateTargetStrengthFaster
        // (HEAD, cancellable) - the wire and non-wire power calculations are combined into a single pass so that the blocks
        // around the wire are only read once.
        return net.caffeinemc.mods.lithium.common.block.redstone.RedstoneWirePowerCalculations.getNeighborSignal(this.wireBlock, this, level, pos, false, false);
    }
}