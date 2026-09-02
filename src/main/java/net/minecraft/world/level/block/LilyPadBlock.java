package net.minecraft.world.level.block;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class LilyPadBlock extends VegetationBlock {
    public static final MapCodec<LilyPadBlock> CODEC = simpleCodec(LilyPadBlock::new);
    private static final VoxelShape SHAPE = Block.column(14.0, 0.0, 1.5);
    // MODIFIED for porting: was VFP block/shape MixinLilyPadBlock#viaFabricPlus$shape_r1_8_x (@Unique constant)
    private static final VoxelShape vfpShapeR1_8X = Block.box(0.0, 0.0, 0.0, 16.0, 0.25, 16.0);

    @Override
    public MapCodec<LilyPadBlock> codec() {
        return CODEC;
    }

    protected LilyPadBlock(final BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected void entityInside(
        final BlockState state,
        final Level level,
        final BlockPos pos,
        final Entity entity,
        final InsideBlockEffectApplier effectApplier,
        final boolean isPrecise
    ) {
        super.entityInside(state, level, pos, entity, effectApplier, isPrecise);
        if (level instanceof ServerLevel && entity instanceof AbstractBoat) {
            level.destroyBlock(new BlockPos(pos), true, entity);
        }
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        // MODIFIED for porting: was VFP block/shape MixinLilyPadBlock#changeOutlineShape (@Inject HEAD, cancellable)
        // <= 1.8 drew a lily pad as a paper-thin full 16x16 plate rather than the modern 14-wide 1.5px pad. This also
        // moves collision, since LilyPadBlock inherits BlockBehaviour#getCollisionShape which delegates to getShape.
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)) {
            return vfpShapeR1_8X;
        }

        return SHAPE;
    }

    @Override
    protected boolean mayPlaceOn(final BlockState state, final BlockGetter level, final BlockPos pos) {
        FluidState fluidState = level.getFluidState(pos);
        FluidState fluidAbove = level.getFluidState(pos.above());
        return (fluidState.is(FluidTags.SUPPORTS_LILY_PAD) || state.is(BlockTags.SUPPORTS_LILY_PAD)) && fluidAbove.is(Fluids.EMPTY);
    }
}