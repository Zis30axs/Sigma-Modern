package net.minecraft.world.level;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public class EntityBasedExplosionDamageCalculator extends ExplosionDamageCalculator {
    private final Entity source;

    public EntityBasedExplosionDamageCalculator(final Entity source) {
        this.source = source;
    }

    // MODIFIED for porting: lithium alloc.explosion_behavior EntityBasedExplosionDamageCalculatorMixin avoids the
    // lambda and the extra Optional that Optional#map allocates on every explosion ray step.
    @Override
    public Optional<Float> getBlockExplosionResistance(
        final Explosion explosion, final BlockGetter level, final BlockPos pos, final BlockState block, final FluidState fluid
    ) {
        Optional<Float> optionalBlastResistance = super.getBlockExplosionResistance(explosion, level, pos, block, fluid);
        if (optionalBlastResistance.isPresent()) {
            float blastResistance = optionalBlastResistance.get();
            float effectiveExplosionResistance = this.source.getBlockExplosionResistance(explosion, level, pos, block, fluid, blastResistance);
            if (effectiveExplosionResistance != blastResistance) {
                return Optional.of(effectiveExplosionResistance);
            }
        }

        return optionalBlastResistance;
    }

    @Override
    public boolean shouldBlockExplode(final Explosion explosion, final BlockGetter level, final BlockPos pos, final BlockState state, final float power) {
        return this.source.shouldBlockExplode(explosion, level, pos, state, power);
    }
}